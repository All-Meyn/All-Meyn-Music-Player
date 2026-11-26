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
constexpr double kPi = 3.14159265358979323846;
constexpr float kClipperKnee = 0.891f;  // -1 dBFS
constexpr float kDetectorFloor = 1.0e-10f;
constexpr float kImpactEnvFloor = 1.0e-5f;
// ---- Vocal & Detail Restoration ----
constexpr double kVocalLowHz = 300.0, kVocalHighHz = 4500.0, kAirHz = 9000.0;
constexpr double kAttFastS = 0.001, kRelFastS = 0.015, kAttSlowS = 0.035, kRelSlowS = 0.120;
constexpr float kPresenceTransientGain = 4.5f;  // attack boost per unit of (fast - slow) envelope
constexpr float kDetailMaxLift = 0.45f;         // up to +45 % (~+3.2 dB) on a quiet vocal band
constexpr float kDetailFloor = 0.0005f, kDetailCeiling = 0.06f;  // band level where Detail acts
constexpr float kAirDrive = 2.5f, kAirMax = 0.6f;    // treble lift: (drive - 1) x max = +0.9 x band
constexpr float kHarmonicLevel = 0.3f;             // harmonics at 100 %: ~-10 dB below their source
constexpr double kHarmonicAttackS = 0.001, kHarmonicReleaseS = 0.05;
constexpr double kLosslessFillHz = 18000.0;        // lossless: no empty band; harmonics stay above this
constexpr double kButterQ1 = 0.5411961, kButterQ2 = 1.3065630;  // 4th-order Butterworth cascade
constexpr double kAirSlewSeconds = 0.05;       // Air's harmonic band glides to a new cutoff
constexpr double kMinHarmonicHz = 5000.0;      // lossy: harmonics from cutoff/2, but not below this
// ---- Cutoff detection ----
constexpr double kProbeLowHz = 10000.0, kProbeStepHz = 1000.0;
constexpr double kProbeWindowSeconds = 0.05, kProbeAverageSeconds = 3.0;
constexpr double kProbeMaxRate = 192000.0;     // above this, no detection (no lossy hi-res codecs)
constexpr double kLoudFloor = 1.0e-10;         // window counts if its 10 kHz power is above this
constexpr double kContentFloor = 1.0e-14;      // a probe below this holds no content
constexpr double kLossyCliffDb = 25.0, kLosslessCliffDb = 35.0;   // lossless needs a steeper cliff
constexpr double kLossyEvidenceS = 1.5, kLosslessEvidenceS = 5.0;  // ...and more music
constexpr int kCandidateRuns = 20;             // a changed result must hold ~1 s
constexpr float kFullRangeHz = 19000.0f;

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

// ---- Biquad / VocalState --------------------------------------------------------------------

void Biquad::setup(double cutoffHz, double sampleRate, double q) {
    const double w = 2.0 * 3.14159265358979323846 * cutoffHz / sampleRate;
    const double cw = std::cos(w), alpha = std::sin(w) / (2.0 * q), a0 = 1.0 + alpha;
    b0 = (float) ((1.0 - cw) * 0.5 / a0);
    b1 = (float) ((1.0 - cw) / a0);
    b2 = b0;
    a1 = (float) (-2.0 * cw / a0);
    a2 = (float) ((1.0 - alpha) / a0);
    reset();
}

void Biquad::setupHighpass(double cutoffHz, double sampleRate, double q) {
    const double w = 2.0 * 3.14159265358979323846 * cutoffHz / sampleRate;
    const double cw = std::cos(w), alpha = std::sin(w) / (2.0 * q), a0 = 1.0 + alpha;
    b0 = (float) ((1.0 + cw) * 0.5 / a0);
    b1 = (float) (-(1.0 + cw) / a0);
    b2 = b0;
    a1 = (float) (-2.0 * cw / a0);
    a2 = (float) ((1.0 - alpha) / a0);
}

void Biquad::snapToZero() { snap(z1); snap(z2); }

void VocalState::reset() {
    lp = bp = envFast = envSlow = airHp = airHp2 = airEnv = 0;
    up1.reset(); up2.reset(); down1.reset(); down2.reset(); fill1.reset(); fill2.reset();
    gain = 1;
}

// ---- CutoffDetector ---------------------------------------------------------------------------

void CutoffDetector::setup(double sampleRate) {
    window = std::max(64, (int) std::lround(sampleRate * kProbeWindowSeconds));
    windowSeconds = window / sampleRate;
    decay = std::exp(-windowSeconds / kProbeAverageSeconds);
    const double step = 2.0 * 3.14159265358979323846 / window;
    cosStep = std::cos(step);
    sinStep = std::sin(step);
    probes = 0;
    if (sampleRate <= kProbeMaxRate) {
        for (int k = 0; k < kMaxProbes; k++) {
            const double f = kProbeLowHz + k * kProbeStepHz;
            if (f >= 0.45 * sampleRate) break;
            hz[probes] = (float) f;
            coeff[probes] = 2.0 * std::cos(2.0 * 3.14159265358979323846 * f / sampleRate);
            probes++;
        }
    }
    setSource(false, 0);
}

void CutoffDetector::setSource(bool isLossy, float priorHz) {
    lossy = isLossy;
    cutoff = isLossy && priorHz > 0 && priorHz < kFullRangeHz ? priorHz : 0.0f;
    candidate = -1;
    candidateRuns = 0;
    evidence = 0;
    pos = 0;
    wc = 1; ws = 0;
    for (int k = 0; k < kMaxProbes; k++) s1[k] = s2[k] = acc[k] = 0;
}

void CutoffDetector::process(float x) {
    if (probes < 2) return;
    const double w = 0.5 - 0.5 * wc;  // Hann
    const double xw = x * w;
    for (int k = 0; k < probes; k++) {
        const double s0 = xw + coeff[k] * s1[k] - s2[k];
        s2[k] = s1[k];
        s1[k] = s0;
    }
    const double c = wc * cosStep - ws * sinStep;
    ws = wc * sinStep + ws * cosStep;
    wc = c;
    if (++pos >= window) endWindow();
}

void CutoffDetector::endWindow() {
    pos = 0;
    wc = 1; ws = 0;
    // Power, normalised so a sine of amplitude A at the probe reads A^2 (Hann coherent gain 0.5).
    const double norm = 16.0 / ((double) window * window);
    double p[kMaxProbes] {};
    for (int k = 0; k < probes; k++) {
        p[k] = std::max(0.0, s1[k] * s1[k] + s2[k] * s2[k] - coeff[k] * s1[k] * s2[k]) * norm;
        s1[k] = s2[k] = 0;
    }
    if (p[0] < kLoudFloor) return;  // quiet: keep the last result
    for (int k = 0; k < probes; k++) acc[k] = acc[k] * decay + p[k];
    evidence += windowSeconds;
    if (evidence < (lossy ? kLossyEvidenceS : kLosslessEvidenceS)) return;

    const double ratio = std::pow(10.0, -(lossy ? kLossyCliffDb : kLosslessCliffDb) / 10.0);
    float found = 0;
    double above = 0;  // loudest probe above k
    for (int k = probes - 1; k >= 0; k--) {
        if (k < probes - 1 && acc[k] > kContentFloor && above < acc[k] * ratio) found = hz[k] + 500.0f;
        above = std::max(above, acc[k]);
    }
    if (found >= kFullRangeHz) found = 0;
    if (found == cutoff) { candidateRuns = 0; return; }
    if (found != candidate) { candidate = found; candidateRuns = 0; }
    if (++candidateRuns >= kCandidateRuns) { cutoff = found; candidateRuns = 0; }
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

namespace {
// "Nothing offered" in Chain::convOffered (null means "remove the convolution").
char gNoOffer;
ConvKernel* noOffer() { return reinterpret_cast<ConvKernel*>(&gNoOffer); }
}  // namespace

Chain::Chain(double sampleRate, int channels) : sr(sampleRate), chans(std::min(std::max(channels, 1), kMaxChannels)) {
    convOffered.store(noOffer());
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
    airC2 = airC2Target = onePoleHz(kAirHz, 2.0 * sr);
    airSlewC = onePole(kAirSlewSeconds, sr);
    airEnvAttC = onePole(kHarmonicAttackS, 2.0 * sr);
    airEnvRelC = onePole(kHarmonicReleaseS, 2.0 * sr);
    voc.fill1.setupHighpass(kLosslessFillHz, 2.0 * sr, kButterQ1);
    voc.fill2.setupHighpass(kLosslessFillHz, 2.0 * sr, kButterQ2);
    cutoffDet.setup(sr);
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
                        &vocalMix, &presence, &detail, &air, &eqMix, &eqPreamp, &convMix})
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
    for (auto& f : eqFilters) f.reset();
    compFresh = bassFresh = impFresh = vocFresh = eqFresh = convFresh = true;
    m = Meters {};
}

namespace {
// A detector reading [db] (neutral = at its pivot: 0 dB gain).
void primeDetector(float db, float& meanSquare, float& levelDb) {
    levelDb = db;
    meanSquare = std::pow(10.0f, db / 10.0f);
}
}  // namespace

void EqFilter::design(const EqBand& band, double fs) {
    const double fc = std::clamp((double) band.freqHz, 10.0, 0.49 * fs);
    const double q = std::clamp((double) band.q, 0.05, 50.0);
    const double gain = std::clamp((double) band.gainDb, -30.0, 30.0);
    const double A = std::pow(10.0, gain / 40.0);
    const double w = 2.0 * kPi * fc / fs;
    const double c = std::cos(w), alpha = std::sin(w) / (2.0 * q), sA = std::sqrt(A);
    double nb0, nb1, nb2, na0, na1, na2;
    switch (band.type) {
        case kEqLowShelf:
            nb0 = A * ((A + 1) - (A - 1) * c + 2 * sA * alpha);
            nb1 = 2 * A * ((A - 1) - (A + 1) * c);
            nb2 = A * ((A + 1) - (A - 1) * c - 2 * sA * alpha);
            na0 = (A + 1) + (A - 1) * c + 2 * sA * alpha;
            na1 = -2 * ((A - 1) + (A + 1) * c);
            na2 = (A + 1) + (A - 1) * c - 2 * sA * alpha;
            break;
        case kEqHighShelf:
            nb0 = A * ((A + 1) + (A - 1) * c + 2 * sA * alpha);
            nb1 = -2 * A * ((A - 1) + (A + 1) * c);
            nb2 = A * ((A + 1) + (A - 1) * c - 2 * sA * alpha);
            na0 = (A + 1) - (A - 1) * c + 2 * sA * alpha;
            na1 = 2 * ((A - 1) - (A + 1) * c);
            na2 = (A + 1) - (A - 1) * c - 2 * sA * alpha;
            break;
        case kEqLowPass:
            nb0 = (1 - c) / 2; nb1 = 1 - c; nb2 = (1 - c) / 2;
            na0 = 1 + alpha; na1 = -2 * c; na2 = 1 - alpha;
            break;
        case kEqHighPass:
            nb0 = (1 + c) / 2; nb1 = -(1 + c); nb2 = (1 + c) / 2;
            na0 = 1 + alpha; na1 = -2 * c; na2 = 1 - alpha;
            break;
        default:  // peak
            nb0 = 1 + alpha * A; nb1 = -2 * c; nb2 = 1 - alpha * A;
            na0 = 1 + alpha / A; na1 = -2 * c; na2 = 1 - alpha / A;
            break;
    }
    b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0;
}

double EqFilter::magnitudeDb(double hz, double fs) const {
    const double w = 2.0 * kPi * hz / fs;
    const double cr = std::cos(w), ci = -std::sin(w), c2r = std::cos(2 * w), c2i = -std::sin(2 * w);
    const double nr = b0 + b1 * cr + b2 * c2r, ni = b1 * ci + b2 * c2i;
    const double dr = 1 + a1 * cr + a2 * c2r, di = a1 * ci + a2 * c2i;
    return 10.0 * std::log10((nr * nr + ni * ni) / (dr * dr + di * di));
}

void Chain::runEq(float x[kMaxChannels], int n) {
    const float mix = eqMix.next();
    const float pre = dbToGain(eqPreamp.next());
    if (mix <= 0.0f && eqMix.idle()) { eqFresh = true; return; }  // off: untouched, no work
    if (eqFresh) {  // switching on: fresh filters (no stale ringing)
        for (int i = 0; i < kMaxEqBands; i++) eqFilters[i].reset();
        eqFresh = false;
    }
    for (int c = 0; c < n; c++) {
        double y = x[c] * (double) pre;
        for (int i = 0; i < eqCount; i++) y = eqFilters[i].process(c, y);
        x[c] += mix * ((float) y - x[c]);
    }
}

Chain::~Chain() {
    freeConvKernel(conv);
    ConvKernel* o = convOffered.exchange(noOffer());
    if (o != noOffer()) freeConvKernel(o);
    freeConvKernel(convRetired.exchange(nullptr));
}

void Chain::collectConvolution() { freeConvKernel(convRetired.exchange(nullptr)); }

void Chain::offerConvolution(ConvKernel* kernel) {
    collectConvolution();
    ConvKernel* before = convOffered.exchange(kernel);
    if (before != noOffer()) freeConvKernel(before);  // never taken by the audio thread
}

void Chain::takeOfferedConvolution() {
    if (convRetired.load(std::memory_order_acquire) != nullptr) return;  // last one not collected yet
    ConvKernel* k = convOffered.exchange(noOffer(), std::memory_order_acq_rel);
    if (k == noOffer()) return;
    if (k != nullptr && convKernelRate(k) != sr) {  // built for another rate: give it back
        convRetired.store(k, std::memory_order_release);
        return;
    }
    ConvKernel* old = conv;
    conv = k;
    convFresh = true;
    convLatency.store(convKernelLatency(k), std::memory_order_relaxed);
    if (old) convRetired.store(old, std::memory_order_release);
}

void Chain::runConv(float x[kMaxChannels], int n) {
    const float mix = convMix.next();
    if (conv == nullptr || (mix <= 0.0f && convMix.idle())) { convFresh = true; return; }
    if (convFresh) { convReset(conv); convFresh = false; }  // no stale audio from before
    float dry[kMaxChannels];
    for (int c = 0; c < n; c++) dry[c] = x[c];
    convProcessFrame(conv, x, n);
    for (int c = 0; c < n; c++) x[c] = dry[c] + mix * (x[c] - dry[c]);
}

void Chain::setParams(const Params& p) {
    const bool snapNow = first;  // the first call jumps straight to the values
    first = false;
    convMix.set(p.convOn ? 1.0f : 0.0f, snapNow);
    // Headphone EQ: redesign only the bands that changed (their state is kept, so no click).
    eqMix.set(p.eqOn ? 1.0f : 0.0f, snapNow);
    eqPreamp.set(clampf(p.eqPreampDb, -30.0f, 10.0f), snapNow);
    const int count = std::clamp(p.eqBandCount, 0, kMaxEqBands);
    for (int i = 0; i < count; i++) {
        if (i >= eqCount || !(p.eqBands[i] == eqDesigned[i])) {
            if (i >= eqCount) eqFilters[i].reset();
            eqFilters[i].design(p.eqBands[i], sr);
            eqDesigned[i] = p.eqBands[i];
        }
    }
    eqCount = count;
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

// One sample of the Air stage's harmonics at twice the sample rate. The source band (above ~9 kHz,
// or above half a lossy cutoff) is normalised by its own level, turned into clean 2nd and 3rd
// harmonics (Chebyshev T2, T3: a sine in gives exactly 2f and 3f out), scaled back to ~-10 dB below
// the source, and highpassed at the fill frequency (the cutoff, or 18 kHz on a full-range source)
// so they only land where the codec left nothing. Then the anti-aliasing lowpass. (The linear
// treble lift is added at the normal rate instead: through these filters it would arrive late and
// partly cancel the treble it lifts.)
float Chain::airSubSample(float x, float amount) {
    const float x2 = voc.up2.process(voc.up1.process(x));
    voc.airHp2 += airC2 * (x2 - voc.airHp2);
    const float high = x2 - voc.airHp2;
    const float a = std::fabs(high);
    voc.airEnv += (a > voc.airEnv ? airEnvAttC : airEnvRelC) * (a - voc.airEnv);
    const float peak = voc.airEnv * 1.5707963f + 1.0e-9f;  // mean |sine| x pi/2 = its peak
    const float u = clampf(high / peak, -1.0f, 1.0f);
    const float harmonics = 0.5f * (2.0f * u * u - 1.0f) + 0.5f * u * (4.0f * u * u - 3.0f);
    const float fill = voc.fill2.process(voc.fill1.process(harmonics * peak * kHarmonicLevel * amount));
    return voc.down2.process(voc.down1.process(fill));
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

    // Lossy source: Air's harmonics come from the octave below the cutoff, filling above it.
    cutoffDet.process(mid);
    const float cut = cutoffDet.cutoffHz();
    if (cut != lastCutoff) {
        lastCutoff = cut;
        const double fh = cut > 0 ? std::min(kAirHz, std::max(kMinHarmonicHz, 0.5 * cut)) : kAirHz;
        airC2Target = onePoleHz(fh, 2.0 * sr);
        const double fill = cut > 0 ? cut : kLosslessFillHz;
        voc.fill1.setupHighpass(fill, 2.0 * sr, kButterQ1);
        voc.fill2.setupHighpass(fill, 2.0 * sr, kButterQ2);
    }
    airC2 += airSlewC * (airC2Target - airC2);

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
    takeOfferedConvolution();  // a new convolution kernel, if one was handed over
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
        runEq(x, chans);    // headphone correction last, before volume and soft clip
        runConv(x, chans);  // (convolution EQ right after the parametric one)
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
    voc.fill1.snapToZero(); voc.fill2.snapToZero(); snap(voc.airEnv);
    for (int i = 0; i < eqCount; i++)
        for (int c = 0; c < kMaxChannels; c++) {
            if (std::fabs(eqFilters[i].z1[c]) < 1e-30) eqFilters[i].z1[c] = 0;
            if (std::fabs(eqFilters[i].z2[c]) < 1e-30) eqFilters[i].z2[c] = 0;
        }
    m.vocalDb = vocFresh ? 0.0f : vocalMix.value() * amplitudeToDb(voc.gain);
    m.airCutoffHz = cutoffDet.cutoffHz();
    m.outputPeak = peak;
}

}  // namespace allmeyn::dsp
