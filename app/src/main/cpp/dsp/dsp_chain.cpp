#include "dsp_chain.h"

#include <algorithm>
#include <cmath>

namespace allmeyn::dsp {

namespace {
// ---- Tetra-Expand constants (ExpanderConstants.h, EXPANDER_NUM_BANDS == 4) ----
constexpr float kCrossoverHz[kBands - 1] = {150.0f, 1000.0f, 6000.0f};
constexpr float kRmsSeconds[kBands] = {0.050f, 0.015f, 0.008f, 0.005f};
constexpr float kLevelAttackSeconds[kBands] = {0.030f, 0.020f, 0.012f, 0.008f};
constexpr float kLevelReleaseSeconds[kBands] = {0.400f, 0.300f, 0.200f, 0.150f};
constexpr float kImpactFastReleaseSeconds[kBands] = {0.080f, 0.030f, 0.015f, 0.010f};
constexpr float kImpactSlowSeconds[kBands] = {0.150f, 0.100f, 0.060f, 0.040f};
constexpr float kImpactReleaseBandScale[kBands] = {1.5f, 1.15f, 0.85f, 0.6f};
// Band-width compensation: narrow mid bands carry less energy, so their pivot is lowered.
constexpr float kBandPivotOffsetDb[kBands] = {0.0f, -2.89f, -3.14f, 0.0f};
constexpr float kBassCrossoverHz = 150.0f;   // Bass Restoration acts below 150 Hz (Tetra's LF band)
constexpr float kMaxBassExtraRatio = 0.5f;   // 100 % Bass = +0.5 on the ratio
constexpr float kMaxExpansionPercent = 50.0f;
constexpr float kMaxUpDb = 12.0f;
constexpr float kMaxDownDb = -40.0f;
constexpr float kMaxImpactDb = 12.0f;
constexpr float kImpactSensitivity = 0.5f;
constexpr float kImpactFastAttackSeconds = 0.0005f;
constexpr float kImpactDeadbandDb = 2.0f;
constexpr double kSmoothingSeconds = 0.05;
constexpr float kClipperKnee = 0.891f;  // -1 dBFS
constexpr float kDetectorFloor = 1.0e-10f;
constexpr float kImpactEnvFloor = 1.0e-5f;
// ---- Vocal & Detail Restoration ----
constexpr double kVocalLowHz = 300.0, kVocalHighHz = 4500.0, kAirHz = 9000.0;
constexpr double kAttFastS = 0.001, kRelFastS = 0.015, kAttSlowS = 0.035, kRelSlowS = 0.120;
constexpr float kPresenceTransientGain = 4.5f;  // attack boost per unit of (fast - slow) envelope
constexpr float kDetailMaxLift = 0.45f;         // up to +45 % (~+3.2 dB) on a quiet vocal band
constexpr float kDetailFloor = 0.0005f, kDetailCeiling = 0.06f;  // band level where Detail acts
constexpr float kAirDrive = 2.5f, kAirEven = 0.25f, kAirMax = 0.6f;
constexpr double kButterQ1 = 0.5411961, kButterQ2 = 1.3065630;  // 4th-order Butterworth cascade

inline float powerToDb(float p) { return 10.0f * std::log10(p); }
inline float amplitudeToDb(float a) { return 20.0f * std::log10(a); }
inline float dbToGain(float db) { return std::exp(db * 0.11512925464970229f); }
inline float clampf(float v, float lo, float hi) { return std::min(hi, std::max(lo, v)); }

float onePole(double seconds, double sr) { return (float) (1.0 - std::exp(-1.0 / (seconds * sr))); }
float onePoleHz(double hz, double sr) { return (float) (1.0 - std::exp(-2.0 * 3.14159265358979323846 * hz / sr)); }
float pole(double seconds, double sr) { return (float) std::exp(-1.0 / (seconds * sr)); }

float softClipSample(float x) {
    const float a = std::fabs(x);
    if (a <= kClipperKnee) return x;
    const float range = 1.0f - kClipperKnee;
    return std::copysign(kClipperKnee + range * std::tanh((a - kClipperKnee) / range), x);
}

inline void snap(float& v) { if (std::fabs(v) < 1.0e-15f) v = 0; }
}  // namespace

// ---- LR4 --------------------------------------------------------------------------------------

void LR4::setup(double cutoffHz, double sampleRate) {
    g = (float) std::tan(3.14159265358979323846 * cutoffHz / sampleRate);
    r2 = (float) std::sqrt(2.0);
    h = (float) (1.0 / (1.0 + r2 * g + g * g));
    reset();
}

void LR4::reset() {
    for (int c = 0; c < kMaxChannels; c++) s1[c] = s2[c] = s3[c] = s4[c] = 0;
}

void LR4::split(int ch, float x, float& low, float& high) {
    const float yH = (x - (r2 + g) * s1[ch] - s2[ch]) * h;
    const float yB = g * yH + s1[ch];
    s1[ch] = g * yH + yB;
    const float yL = g * yB + s2[ch];
    s2[ch] = g * yB + yL;
    const float yH2 = (yL - (r2 + g) * s3[ch] - s4[ch]) * h;
    const float yB2 = g * yH2 + s3[ch];
    s3[ch] = g * yH2 + yB2;
    const float yL2 = g * yB2 + s4[ch];
    s4[ch] = g * yB2 + yL2;
    low = yL2;
    high = yL - r2 * yB + yH - yL2;  // allpass - lowpass: low + high sums to the allpass
}

float LR4::allpass(int ch, float x) {
    const float yH = (x - (r2 + g) * s1[ch] - s2[ch]) * h;
    const float yB = g * yH + s1[ch];
    s1[ch] = g * yH + yB;
    const float yL = g * yB + s2[ch];
    s2[ch] = g * yB + yL;
    return yL - r2 * yB + yH;
}

void LR4::snapToZero() {
    for (int c = 0; c < kMaxChannels; c++) { snap(s1[c]); snap(s2[c]); snap(s3[c]); snap(s4[c]); }
}

// ---- BiquadLP / VocalState --------------------------------------------------------------------

void BiquadLP::setup(double cutoffHz, double sampleRate, double q) {
    const double w = 2.0 * 3.14159265358979323846 * cutoffHz / sampleRate;
    const double cw = std::cos(w), alpha = std::sin(w) / (2.0 * q), a0 = 1.0 + alpha;
    b0 = (float) ((1.0 - cw) * 0.5 / a0);
    b1 = (float) ((1.0 - cw) / a0);
    b2 = b0;
    a1 = (float) (-2.0 * cw / a0);
    a2 = (float) ((1.0 - alpha) / a0);
    reset();
}

void BiquadLP::snapToZero() { snap(z1); snap(z2); }

void VocalState::reset() {
    lp = bp = envFast = envSlow = airHp = airHp2 = 0;
    up1.reset(); up2.reset(); down1.reset(); down2.reset();
    gain = 1;
}

// ---- Smoothed ---------------------------------------------------------------------------------

void Smoothed::reset(double sampleRate, double seconds) {
    steps = (int) std::floor(sampleRate * seconds);
    current = target;
    countdown = 0;
}

void Smoothed::set(float v, bool snapNow) {
    if (snapNow || steps <= 0) { current = target = v; countdown = 0; return; }
    if (v == target) return;
    target = v;
    countdown = steps;
    step = (target - current) / (float) countdown;
}

float Smoothed::next() {
    if (countdown <= 0) return target;
    if (--countdown == 0) current = target; else current += step;
    return current;
}

// ---- BandSplitter -----------------------------------------------------------------------------

int BandSplitter::allpassIndex(int band, int crossover) {
    // Allpasses for band b cover crossovers b+1 .. kBands-2, stored band by band.
    int offset = 0;
    for (int b = 0; b < band; b++) offset += (kBands - 1) - 1 - b;
    return offset + (crossover - band - 1);
}

void BandSplitter::setup(double sampleRate) {
    for (int k = 0; k < kBands - 1; k++) splits[k].setup(kCrossoverHz[k], sampleRate);
    for (int b = 0; b < kBands - 1; b++)
        for (int k = b + 1; k < kBands - 1; k++) allpasses[allpassIndex(b, k)].setup(kCrossoverHz[k], sampleRate);
}

void BandSplitter::reset() {
    for (auto& f : splits) f.reset();
    for (auto& f : allpasses) f.reset();
}

void BandSplitter::process(int ch, float x, float out[kBands]) {
    float rest = x;
    for (int k = 0; k < kBands - 1; k++) {
        float low, high;
        splits[k].split(ch, rest, low, high);
        for (int a = k + 1; a < kBands - 1; a++) low = allpasses[allpassIndex(k, a)].allpass(ch, low);
        out[k] = low;
        rest = high;
    }
    out[kBands - 1] = rest;
}

void BandSplitter::snapToZero() {
    for (auto& f : splits) f.snapToZero();
    for (auto& f : allpasses) f.snapToZero();
}

// ---- Chain ------------------------------------------------------------------------------------

Chain::Chain(double sampleRate, int channels) : sr(sampleRate), chans(std::min(std::max(channels, 1), kMaxChannels)) {
    compSplit.setup(sr);
    impSplit.setup(sr);
    bassSplit.setup(kBassCrossoverHz, sr);
    for (int b = 0; b < kBands; b++) {
        compDet[b].rmsC = onePole(kRmsSeconds[b], sr);
        compDet[b].attC = onePole(kLevelAttackSeconds[b], sr);
        compDet[b].relC = onePole(kLevelReleaseSeconds[b], sr);
        trans[b].fastAttC = onePole(kImpactFastAttackSeconds, sr);
        trans[b].fastRelC = onePole(kImpactFastReleaseSeconds[b], sr);
        trans[b].slowC = onePole(kImpactSlowSeconds[b], sr);
        trans[b].releaseScale = kImpactReleaseBandScale[b];
        trans[b].releaseC = 0;
    }
    bassDet.rmsC = onePole(kRmsSeconds[0], sr);  // the bass band uses Tetra's LF timings
    bassDet.attC = onePole(kLevelAttackSeconds[0], sr);
    bassDet.relC = onePole(kLevelReleaseSeconds[0], sr);
    vocLowC = onePoleHz(kVocalLowHz, sr);
    vocHighC = onePoleHz(kVocalHighHz, sr);
    airC = onePoleHz(kAirHz, sr);
    airC2 = onePoleHz(kAirHz, 2.0 * sr);
    attFast = pole(kAttFastS, sr);
    relFast = pole(kRelFastS, sr);
    attSlow = pole(kAttSlowS, sr);
    relSlow = pole(kRelSlowS, sr);
    const double aaHz = std::min(19500.0, sr * 0.44);  // below the original Nyquist
    voc.up1.setup(aaHz, 2.0 * sr, kButterQ1);
    voc.up2.setup(aaHz, 2.0 * sr, kButterQ2);
    voc.down1.setup(aaHz, 2.0 * sr, kButterQ1);
    voc.down2.setup(aaHz, 2.0 * sr, kButterQ2);
    for (Smoothed* s : {&compMix, &bassMix, &impMix, &ratioMinusOne, &transition, &hfTransition,
                        &bassRatio, &bassPivot, &impactAmount, &volumeDb,
                        &vocalMix, &presence, &detail, &air})
        s->reset(sr, kSmoothingSeconds);
    reset();
}

void Chain::reset() {
    compSplit.reset();
    impSplit.reset();
    bassSplit.reset();
    for (int b = 0; b < kBands; b++) {
        compDet[b].meanSquare = 0;
        compDet[b].levelDb = powerToDb(kDetectorFloor);
        trans[b].fastEnv = 0;
        trans[b].slowEnvDb = amplitudeToDb(kImpactEnvFloor);
        trans[b].gainDb = 0;
    }
    bassDet.meanSquare = 0;
    bassDet.levelDb = powerToDb(kDetectorFloor);
    voc.reset();
    compFresh = bassFresh = impFresh = vocFresh = true;
    m = Meters {};
}

namespace {
// A detector reading [db] (neutral = at its pivot: 0 dB gain).
void primeDetector(float db, float& meanSquare, float& levelDb) {
    levelDb = db;
    meanSquare = std::pow(10.0f, db / 10.0f);
}
}  // namespace

void Chain::setParams(const Params& p) {
    const bool snapNow = first;  // the first call jumps straight to the values
    first = false;
    compMix.set(p.companderOn ? 1.0f : 0.0f, snapNow);
    bassMix.set(p.bassOn ? 1.0f : 0.0f, snapNow);
    impMix.set(p.impactOn ? 1.0f : 0.0f, snapNow);
    ratioMinusOne.set(clampf(p.expansionPercent, -kMaxExpansionPercent, kMaxExpansionPercent) / 100.0f, snapNow);
    transition.set(p.transitionDb, snapNow);
    hfTransition.set(p.hfTransitionDb, snapNow);
    bassRatio.set(clampf(p.bassPercent, 0.0f, 100.0f) / 100.0f * kMaxBassExtraRatio, snapNow);
    bassPivot.set(p.bassTransitionDb, snapNow);
    impactAmount.set(clampf(p.impactPercent, 0.0f, 100.0f) / 100.0f, snapNow);
    volumeDb.set(p.volumeDb, snapNow);
    vocalMix.set(p.vocalOn ? 1.0f : 0.0f, snapNow);
    presence.set(clampf(p.presencePercent, 0.0f, 100.0f) / 100.0f, snapNow);
    detail.set(clampf(p.detailPercent, 0.0f, 100.0f) / 100.0f, snapNow);
    air.set(clampf(p.airPercent, 0.0f, 100.0f) / 100.0f, snapNow);
    softClip = p.softClip;
    if (p.impactReleaseMs != lastReleaseMs) {
        lastReleaseMs = p.impactReleaseMs;
        for (auto& t : trans) t.releaseC = onePole(0.001 * clampf(p.impactReleaseMs, 1.0f, 10000.0f) * t.releaseScale, sr);
    }
    // Accept the order only if it's a permutation of the processors.
    bool seen[kNumProcessors] = {};
    bool ok = true;
    for (int i = 0; i < kNumProcessors; i++) {
        const int v = p.order[i];
        if (v < 0 || v >= kNumProcessors || seen[v]) { ok = false; break; }
        seen[v] = true;
    }
    if (ok) {
        // Vocal & Detail Restoration first, the others in the given order.
        int k = 0;
        order[k++] = kVocal;
        for (int i = 0; i < kNumProcessors; i++) if (p.order[i] != kVocal) order[k++] = p.order[i];
    }
}

float Chain::levelOf(Detector& d, float meanSquareIn) {
    d.meanSquare += d.rmsC * (meanSquareIn - d.meanSquare);
    const float rmsDb = powerToDb(d.meanSquare + kDetectorFloor);
    d.levelDb += (rmsDb > d.levelDb ? d.attC : d.relC) * (rmsDb - d.levelDb);
    return d.levelDb;
}

float Chain::impactOf(Transient& t, float absIn, float amount) {
    t.fastEnv += (absIn > t.fastEnv ? t.fastAttC : t.fastRelC) * (absIn - t.fastEnv);
    const float fastDb = amplitudeToDb(t.fastEnv + kImpactEnvFloor);
    t.slowEnvDb += t.slowC * (fastDb - t.slowEnvDb);
    const float target = clampf(kImpactSensitivity * (fastDb - t.slowEnvDb - kImpactDeadbandDb), 0.0f, amount * kMaxImpactDb);
    if (target > t.gainDb) t.gainDb = target;  // instant attack
    else t.gainDb += t.releaseC * (target - t.gainDb);
    return t.gainDb;
}

// Each processor: dry in x, processed back into x, crossfaded by its on/off mix. Stereo channels
// share one detector per band (linked), so the image doesn't shift.

void Chain::runCompander(float x[kMaxChannels], int n) {
    const float mix = compMix.next();
    const float r = ratioMinusOne.next(), pivot = transition.next(), hf = hfTransition.next();
    if (mix <= 0.0f && compMix.idle()) {
        if (!compFresh) { compFresh = true; for (float& v : m.companderDb) v = 0; }
        return;
    }
    if (compFresh) {
        compFresh = false;
        compSplit.reset();
        for (int b = 0; b < kBands; b++)
            primeDetector(pivot + kBandPivotOffsetDb[b] + (b == kBands - 1 ? hf : 0.0f), compDet[b].meanSquare, compDet[b].levelDb);
    }
    float band[kMaxChannels][kBands];
    float ms[kBands] = {};
    for (int c = 0; c < n; c++) {
        compSplit.process(c, x[c], band[c]);
        for (int b = 0; b < kBands; b++) ms[b] += band[c][b] * band[c][b];
    }
    float gain[kBands];
    for (int b = 0; b < kBands; b++) {
        const float level = levelOf(compDet[b], ms[b] / (float) n);
        const float bandPivot = pivot + kBandPivotOffsetDb[b] + (b == kBands - 1 ? hf : 0.0f);
        const float db = clampf(r * (level - bandPivot), kMaxDownDb, kMaxUpDb);
        gain[b] = dbToGain(db);
        m.companderDb[b] = mix * db;
    }
    for (int c = 0; c < n; c++) {
        float y = 0;
        for (int b = 0; b < kBands; b++) y += band[c][b] * gain[b];
        x[c] += mix * (y - x[c]);
    }
}

void Chain::runBass(float x[kMaxChannels], int n) {
    const float mix = bassMix.next();
    const float r = bassRatio.next(), pivot = bassPivot.next();
    if (mix <= 0.0f && bassMix.idle()) {
        if (!bassFresh) { bassFresh = true; m.bassDb = 0; }
        return;
    }
    if (bassFresh) {
        bassFresh = false;
        bassSplit.reset();
        primeDetector(pivot, bassDet.meanSquare, bassDet.levelDb);
    }
    float low[kMaxChannels], high[kMaxChannels], ms = 0;
    for (int c = 0; c < n; c++) {
        bassSplit.split(c, x[c], low[c], high[c]);
        ms += low[c] * low[c];
    }
    const float level = levelOf(bassDet, ms / (float) n);
    const float db = clampf(r * (level - pivot), kMaxDownDb, kMaxUpDb);
    const float g = dbToGain(db);
    m.bassDb = mix * db;
    for (int c = 0; c < n; c++) x[c] += mix * (low[c] * g + high[c] - x[c]);
}

void Chain::runImpact(float x[kMaxChannels], int n) {
    const float mix = impMix.next();
    const float amount = impactAmount.next();
    if (mix <= 0.0f && impMix.idle()) {
        if (!impFresh) { impFresh = true; for (float& v : m.impactDb) v = 0; }
        return;
    }
    if (impFresh) {
        impFresh = false;
        impSplit.reset();
        for (auto& t : trans) { t.fastEnv = 0; t.slowEnvDb = 0.0f; t.gainDb = 0; }  // slow starts high: no false boost
    }
    float band[kMaxChannels][kBands];
    float ab[kBands] = {};
    for (int c = 0; c < n; c++) {
        impSplit.process(c, x[c], band[c]);
        for (int b = 0; b < kBands; b++) ab[b] += std::fabs(band[c][b]);
    }
    float gain[kBands];
    for (int b = 0; b < kBands; b++) {
        const float db = impactOf(trans[b], ab[b] / (float) n, amount);
        gain[b] = dbToGain(db);
        m.impactDb[b] = mix * db;
    }
    for (int c = 0; c < n; c++) {
        float y = 0;
        for (int b = 0; b < kBands; b++) y += band[c][b] * gain[b];
        x[c] += mix * (y - x[c]);
    }
}

// One sample of the Air stage's harmonics at twice the sample rate: anti-imaging lowpass, ~9 kHz
// highpass, then only the nonlinear part of the soft saturation (odd harmonics from tanh, even from
// x|x|), then the anti-aliasing lowpass. The linear part (a plain treble lift) is added at the normal
// rate instead: through these filters it would arrive late and partly cancel the treble it lifts.
float Chain::airSubSample(float x, float amount) {
    const float x2 = voc.up2.process(voc.up1.process(x));
    voc.airHp2 += airC2 * (x2 - voc.airHp2);
    const float high = x2 - voc.airHp2;
    const float harmonics = std::tanh(high * kAirDrive) - kAirDrive * high + kAirEven * high * std::fabs(high);
    return voc.down2.process(voc.down1.process(harmonics * amount * kAirMax));
}

void Chain::runVocal(float x[kMaxChannels], int n) {
    const float mix = vocalMix.next();
    const float p = presence.next(), d = detail.next(), a = air.next();
    if (mix <= 0.0f && vocalMix.idle()) {
        if (!vocFresh) { vocFresh = true; m.vocalDb = 0; }
        return;
    }
    if (vocFresh) {
        vocFresh = false;
        voc.reset();
        voc.envFast = voc.envSlow = 1.0f;  // starts at full scale and settles: no false attack or lift
    }
    const float l = x[0], r = n > 1 ? x[1] : x[0];
    const float mid = 0.5f * (l + r), side = 0.5f * (l - r);

    // Vocal band of the mid signal (the phantom centre, where a lead vocal sits).
    voc.lp += vocLowC * (mid - voc.lp);
    voc.bp += vocHighC * ((mid - voc.lp) - voc.bp);
    const float band = voc.bp;

    const float ab = std::fabs(band);
    voc.envFast = ab > voc.envFast ? attFast * voc.envFast + (1.0f - attFast) * ab
                                   : relFast * voc.envFast + (1.0f - relFast) * ab;
    voc.envSlow = ab > voc.envSlow ? attSlow * voc.envSlow + (1.0f - attSlow) * ab
                                   : relSlow * voc.envSlow + (1.0f - relSlow) * ab;

    // Presence: consonants and attacks (fast envelope above the slow one).
    const float transientGain = 1.0f + std::max(0.0f, voc.envFast - voc.envSlow) * p * kPresenceTransientGain;
    // Detail: lift the band when it's quiet; nothing at full level, so peaks aren't pushed up.
    float lift = 1.0f;
    if (voc.envSlow > kDetailFloor && voc.envSlow < kDetailCeiling)
        lift += d * kDetailMaxLift * (1.0f - voc.envSlow / kDetailCeiling);
    voc.gain = transientGain * lift;

    // Air: the treble lift (the linear part of the saturation) at this rate, plus the harmonics at
    // 2x: the input sample (x2 for the zero-stuffing), then a zero, whose output decimation drops.
    voc.airHp += airC * (mid - voc.airHp);
    float airOut = (kAirDrive - 1.0f) * (mid - voc.airHp) * a * kAirMax;
    airOut += airSubSample(mid * 2.0f, a);
    airSubSample(0.0f, a);

    const float outMid = mid + band * (voc.gain - 1.0f) * (0.5f + 0.5f * p) + airOut;
    if (n > 1) {
        x[0] += mix * ((outMid + side) - x[0]);
        x[1] += mix * ((outMid - side) - x[1]);
    } else {
        x[0] += mix * (outMid - x[0]);
    }
}

void Chain::process(float* data, int frames) {
    float peak = 0;
    for (int i = 0; i < frames; i++) {
        float* f = data + (long) i * chans;
        float x[kMaxChannels] = {f[0], chans > 1 ? f[1] : 0.0f};
        for (int slot = 0; slot < kNumProcessors; slot++) {
            switch (order[slot]) {
                case kCompander: runCompander(x, chans); break;
                case kBass: runBass(x, chans); break;
                case kImpact: runImpact(x, chans); break;
                case kVocal: runVocal(x, chans); break;
            }
        }
        const float vol = dbToGain(volumeDb.next());
        for (int c = 0; c < chans; c++) {
            float y = x[c] * vol;
            if (softClip) y = softClipSample(y);
            f[c] = y;
            peak = std::max(peak, std::fabs(y));
        }
    }
    compSplit.snapToZero();
    impSplit.snapToZero();
    bassSplit.snapToZero();
    for (float* v : {&voc.lp, &voc.bp, &voc.envFast, &voc.envSlow, &voc.airHp, &voc.airHp2}) snap(*v);
    voc.up1.snapToZero(); voc.up2.snapToZero(); voc.down1.snapToZero(); voc.down2.snapToZero();
    m.vocalDb = vocFresh ? 0.0f : vocalMix.value() * amplitudeToDb(voc.gain);
    m.outputPeak = peak;
}

}  // namespace allmeyn::dsp
