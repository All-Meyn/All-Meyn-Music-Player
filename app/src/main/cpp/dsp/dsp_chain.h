// All-Meyn DSP chain: Compressor/Expander (4 bands), Bass Restoration, Impact Restoration, Vocal &
// Detail Restoration, and an output stage (Volume, Soft Clip). Each processor has its own
// crossovers and detectors, its own on/off (crossfaded, so toggling never clicks), and the
// processors run in a user-chosen order.
//
// Vocal & Detail Restoration works on the mid (L+R) signal only, so the stereo image is kept:
// Presence lifts consonant attacks in the 300 Hz - 4.5 kHz vocal band, Detail gently raises that
// band when it's quiet (upward expansion, up to about +3 dB), and Air adds harmonics and lift
// above about 9 kHz, generated at twice the sample rate so they don't alias. On lossy sources
// (MP3, AAC...) the harmonics come from the octave below the codec's treble cutoff, so they fill
// the empty band above it; the cutoff comes from the file's bitrate (setSource) and is confirmed
// or corrected by CutoffDetector.
//
// Headphone EQ (parametric, after the processors and before the output stage): up to kMaxEqBands
// RBJ-cookbook biquads (peak, low/high shelf, low/high pass) plus a preamp, in double precision so
// low shelves stay exact at high sample rates. The same filters AutoEq and EqualizerAPO use, so an
// imported ParametricEQ.txt sounds as intended.
//
// The maths and constants are ported from Tetra-Expand (github.com/All-Meyn/Tri-Expand,
// Source/DSP/MultiBandExpander.* and ExpanderConstants.h, GPLv3) without JUCE: the
// Linkwitz-Riley filters reproduce juce::dsp::LinkwitzRileyFilter's TPT structure and the
// parameter smoothing reproduces juce::SmoothedValue (linear, 50 ms). Differences from the plugin:
// impact and bass restoration are separate processors instead of terms in the expander's band
// gain; Expansion goes down to -50 % (compression, ratio down to 1:0.5); no Auto Transition and
// no Auto Headroom.
//
// Real-time safe: process() never allocates.
#pragma once

namespace allmeyn::dsp {

constexpr int kBands = 4;
constexpr int kMaxChannels = 2;

constexpr int kMaxEqBands = 20;

/** Headphone EQ filter types (EqualizerAPO PK, LSC, HSC, LPQ, HPQ). */
enum EqType : int { kEqPeak = 0, kEqLowShelf = 1, kEqHighShelf = 2, kEqLowPass = 3, kEqHighPass = 4 };

struct EqBand {
    int   type = kEqPeak;
    float freqHz = 1000.0f;
    float gainDb = 0.0f;   // peak and shelves only
    float q = 0.707f;
    bool operator==(const EqBand& o) const { return type == o.type && freqHz == o.freqHz && gainDb == o.gainDb && q == o.q; }
};

/** Which processor sits in a chain slot. */
enum Processor : int { kCompander = 0, kBass = 1, kImpact = 2, kVocal = 3, kNumProcessors = 4 };

struct Params {
    // Compressor / Expander
    bool  companderOn = false;
    float expansionPercent = 25.0f;  // -50..+50: ratio 1:0.5 .. 1:1.5 (negative = compression)
    float transitionDb = -20.0f;     // pivot, RMS dBFS
    float hfTransitionDb = 0.0f;     // offset on the top band's pivot
    // Bass Restoration
    bool  bassOn = false;
    float bassPercent = 50.0f;       // 0..100: expansion ratio +0..+0.5 below 150 Hz
    float bassTransitionDb = -20.0f; // pivot of the bass band, RMS dBFS
    // Impact Restoration
    bool  impactOn = false;
    float impactPercent = 50.0f;     // 0..100: ceiling of the boost, 100 % = +12 dB
    float impactReleaseMs = 200.0f;  // 25..1500
    // Vocal & Detail Restoration
    bool  vocalOn = false;
    float presencePercent = 50.0f;   // 0..100: consonant / attack lift in the vocal band
    float detailPercent = 50.0f;     // 0..100: upward expansion of the quiet vocal band
    float airPercent = 25.0f;        // 0..100: harmonics and lift above ~9 kHz
    // Headphone EQ (after the processors, before the output stage)
    bool  eqOn = false;
    float eqPreampDb = 0.0f;         // -30..+10, usually negative to leave headroom for boosts
    int   eqBandCount = 0;           // 0..kMaxEqBands
    EqBand eqBands[kMaxEqBands];
    // Output
    float volumeDb = 0.0f;           // -40..+10
    bool  softClip = false;
    // Processing order of the processors (a permutation of 0..kNumProcessors-1). Vocal & Detail
    // Restoration always runs first: setParams moves it to the front whatever order is given.
    int   order[kNumProcessors] = {kVocal, kCompander, kBass, kImpact};
};

/** Readings for the UI. */
struct Meters {
    float companderDb[kBands];  // expansion (+) / compression or downward expansion (-) per band
    float bassDb;               // Bass Restoration gain
    float impactDb[kBands];     // impact boost per band
    float outputPeak;           // largest |output| in the last call
    float vocalDb;              // Vocal & Detail Restoration gain on the vocal band
    float airCutoffHz;          // treble cutoff Air is working with; 0 = full-range source
};
constexpr int kMeterCount = kBands + 1 + kBands + 1 + 1 + 1;

/** juce::dsp::LinkwitzRileyFilter (LR4, TPT): both outputs, or the matching allpass. */
class LR4 {
public:
    void setup(double cutoffHz, double sampleRate);
    void reset();
    void split(int ch, float x, float& low, float& high);
    float allpass(int ch, float x);
    void snapToZero();
private:
    float g = 0, r2 = 1.41421356f, h = 1;
    float s1[kMaxChannels] {}, s2[kMaxChannels] {}, s3[kMaxChannels] {}, s4[kMaxChannels] {};
};

/** juce::SmoothedValue<float> with linear ramps. */
class Smoothed {
public:
    void reset(double sampleRate, double seconds);
    void set(float v, bool snap);
    float next();
    float value() const { return current; }
    float targetValue() const { return target; }
    bool idle() const { return countdown == 0; }
private:
    float current = 0, target = 0, step = 0;
    int steps = 0, countdown = 0;
};

/** Splits into kBands phase-aligned bands (crossover tree + allpasses) that sum back flat. */
class BandSplitter {
public:
    void setup(double sampleRate);
    void reset();
    void process(int ch, float x, float out[kBands]);
    void snapToZero();
private:
    LR4 splits[kBands - 1];
    LR4 allpasses[(kBands - 1) * (kBands - 2) / 2];
    static int allpassIndex(int band, int crossover);
};

/** 2nd-order lowpass or highpass (RBJ cookbook), transposed direct form II, one channel. */
class Biquad {
public:
    void setup(double cutoffHz, double sampleRate, double q);           // lowpass
    void setupHighpass(double cutoffHz, double sampleRate, double q);   // keeps the state
    void reset() { z1 = z2 = 0; }
    float process(float x) {
        const float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
    void snapToZero();
private:
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;
};

/** One RBJ-cookbook biquad in double precision, transposed direct form II, per channel. */
struct EqFilter {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    double z1[kMaxChannels] {}, z2[kMaxChannels] {};
    /** Coefficients for [band] at [sampleRate]; keeps the state (no click when a band is adjusted). */
    void design(const EqBand& band, double sampleRate);
    double process(int ch, double x) {
        const double y = b0 * x + z1[ch];
        z1[ch] = b1 * x - a1 * y + z2[ch];
        z2[ch] = b2 * x - a2 * y;
        return y;
    }
    void reset() { for (int c = 0; c < kMaxChannels; c++) z1[c] = z2[c] = 0; }
    /** Magnitude in dB at [hz] (for tests and the UI's curve). */
    double magnitudeDb(double hz, double sampleRate) const;
};

/**
 * Finds a lossy codec's treble cutoff: a cliff in the spectrum above which there's (almost)
 * nothing. Hann-windowed Goertzel probes at 10, 11 ... 21 kHz measure ~50 ms windows; their power
 * is averaged over ~3 s, weighted by loudness. A cutoff is a probe whose every higher neighbour is
 * [cliff dB] below it; a natural treble roll-off is a few dB per kHz and never qualifies. Quiet
 * windows don't count, so a quiet passage keeps the last result. Cliffs at 19 kHz and above count
 * as full range (Air's harmonics stop at ~19.5 kHz anyway).
 */
class CutoffDetector {
public:
    static constexpr int kMaxProbes = 12;
    void setup(double sampleRate);
    /** A new song: lossy (with the bitrate's [priorHz] guess, 0 = none) or lossless. Clears evidence. */
    void setSource(bool lossy, float priorHz);
    void process(float x);
    float cutoffHz() const { return cutoff; }
private:
    void endWindow();
    int probes = 0, window = 0, pos = 0;
    double windowSeconds = 0, decay = 0;
    double coeff[kMaxProbes] {}, s1[kMaxProbes] {}, s2[kMaxProbes] {}, acc[kMaxProbes] {};
    float hz[kMaxProbes] {};
    double wc = 1, ws = 0, cosStep = 1, sinStep = 0;  // Hann window phasor
    double evidence = 0;      // seconds of loud audio seen for this song
    bool lossy = false;
    float cutoff = 0, candidate = -1;
    int candidateRuns = 0;
};

/** Vocal & Detail Restoration state (mid signal only). */
struct VocalState {
    float lp = 0, bp = 0;              // vocal band: highpass ~300 Hz, then lowpass ~4.5 kHz
    float envFast = 0, envSlow = 0;    // attack (1 ms / 15 ms) and body (35 ms / 120 ms) followers
    float airHp = 0, airHp2 = 0;       // air highpass ~9 kHz, at 1x and at 2x rate
    float airEnv = 0;                  // level of the harmonic source band (2x rate)
    Biquad up1, up2, down1, down2;     // 4th-order Butterworth anti-imaging / anti-aliasing at 2x
    Biquad fill1, fill2;               // keeps only harmonics above the fill frequency (2x)
    float gain = 1;                    // last vocal-band gain, for the meter
    void reset();
};

class Chain {
public:
    Chain(double sampleRate, int channels);
    void setParams(const Params& p);
    /** Interleaved float frames, in place. */
    void process(float* data, int frames);
    void reset();
    const Meters& meters() const { return m; }
    double sampleRate() const { return sr; }
    int channels() const { return chans; }

private:
    struct Detector {  // two-stage level detector: true RMS, then dB ballistics
        float meanSquare, levelDb;
        float rmsC, attC, relC;
    };
    struct Transient {  // impact: fast peak follower vs its slow dB average
        float fastEnv, slowEnvDb, gainDb;
        float fastAttC, fastRelC, slowC, releaseC, releaseScale;
    };

    static float levelOf(Detector& d, float meanSquareIn);
    static float impactOf(Transient& t, float absIn, float amount);

    void runCompander(float x[kMaxChannels], int n);
    void runBass(float x[kMaxChannels], int n);
    void runImpact(float x[kMaxChannels], int n);
    void runVocal(float x[kMaxChannels], int n);
    void runEq(float x[kMaxChannels], int n);
public:
    /** The song's source, from the file: see CutoffDetector::setSource. */
    void setSource(bool lossy, float priorCutoffHz) { cutoffDet.setSource(lossy, priorCutoffHz); }
private:
    float airSubSample(float x, float amount);

    double sr;
    int chans;
    bool first = true;

    BandSplitter compSplit, impSplit;
    LR4 bassSplit;
    Detector compDet[kBands];
    Detector bassDet;
    Transient trans[kBands];

    Smoothed compMix, bassMix, impMix;  // processor on/off crossfades
    Smoothed ratioMinusOne, transition, hfTransition;
    Smoothed bassRatio, bassPivot;
    Smoothed impactAmount, volumeDb;
    Smoothed vocalMix, presence, detail, air;
    float lastReleaseMs = -1;
    bool softClip = false;

    VocalState voc;
    float vocLowC, vocHighC, airC, airC2;          // one-pole coefficients (airC2 at 2x rate)
    float airC2Target = 0, airSlewC = 0, lastCutoff = -1;
    float airEnvAttC = 0, airEnvRelC = 0;          // harmonic source envelope (2x rate)
    CutoffDetector cutoffDet;
    float attFast, relFast, attSlow, relSlow;      // envelope poles

    // Set when a processor was skipped (off) or the chain was reset: on its next run it starts from
    // fresh filters and neutral detectors (unity gain, no false impact), not from stale or silent state.
    bool compFresh = true, bassFresh = true, impFresh = true, vocFresh = true;

    // Headphone EQ
    EqFilter eqFilters[kMaxEqBands];
    EqBand eqDesigned[kMaxEqBands];
    int eqCount = 0;
    Smoothed eqMix, eqPreamp;
    bool eqFresh = true;

    int order[kNumProcessors] = {kVocal, kCompander, kBass, kImpact};
    Meters m {};
};

}  // namespace allmeyn::dsp
