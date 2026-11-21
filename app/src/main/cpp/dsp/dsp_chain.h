// All-Meyn DSP chain: Compressor/Expander (4 bands), Bass Restoration, Impact Restoration, and an
// output stage (Volume, Soft Clip). Each processor has its own crossovers and detectors, its own
// on/off (crossfaded, so toggling never clicks), and the three processors run in a user-chosen
// order.
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

/** Which processor sits in a chain slot. */
enum Processor : int { kCompander = 0, kBass = 1, kImpact = 2, kNumProcessors = 3 };

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
    // Output
    float volumeDb = 0.0f;           // -40..+10
    bool  softClip = false;
    // Processing order of the three processors (a permutation of 0..2).
    int   order[kNumProcessors] = {kCompander, kBass, kImpact};
};

/** Readings for the UI. */
struct Meters {
    float companderDb[kBands];  // expansion (+) / compression or downward expansion (-) per band
    float bassDb;               // Bass Restoration gain
    float impactDb[kBands];     // impact boost per band
    float outputPeak;           // largest |output| in the last call
};
constexpr int kMeterCount = kBands + 1 + kBands + 1;

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
    float lastReleaseMs = -1;
    bool softClip = false;

    // Set when a processor was skipped (off) or the chain was reset: on its next run it starts from
    // fresh filters and neutral detectors (unity gain, no false impact), not from stale or silent state.
    bool compFresh = true, bassFresh = true, impFresh = true;

    int order[kNumProcessors] = {kCompander, kBass, kImpact};
    Meters m {};
};

}  // namespace allmeyn::dsp
