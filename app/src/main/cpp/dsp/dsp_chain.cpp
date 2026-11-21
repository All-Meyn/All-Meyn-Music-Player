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

inline float powerToDb(float p) { return 10.0f * std::log10(p); }
inline float amplitudeToDb(float a) { return 20.0f * std::log10(a); }
inline float dbToGain(float db) { return std::exp(db * 0.11512925464970229f); }
inline float clampf(float v, float lo, float hi) { return std::min(hi, std::max(lo, v)); }

float onePole(double seconds, double sr) { return (float) (1.0 - std::exp(-1.0 / (seconds * sr))); }

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
    for (Smoothed* s : {&compMix, &bassMix, &impMix, &ratioMinusOne, &transition, &hfTransition,
                        &bassRatio, &bassPivot, &impactAmount, &volumeDb})
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
    compFresh = bassFresh = impFresh = true;
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
    softClip = p.softClip;
    if (p.impactReleaseMs != lastReleaseMs) {
        lastReleaseMs = p.impactReleaseMs;
        for (auto& t : trans) t.releaseC = onePole(0.001 * clampf(p.impactReleaseMs, 1.0f, 10000.0f) * t.releaseScale, sr);
    }
    // Accept the order only if it's a permutation of the three processors.
    bool seen[kNumProcessors] = {};
    bool ok = true;
    for (int i = 0; i < kNumProcessors; i++) {
        const int v = p.order[i];
        if (v < 0 || v >= kNumProcessors || seen[v]) { ok = false; break; }
        seen[v] = true;
    }
    if (ok) for (int i = 0; i < kNumProcessors; i++) order[i] = p.order[i];
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
    m.outputPeak = peak;
}

}  // namespace allmeyn::dsp
