// Host tests for the DSP chain (app/src/main/cpp/dsp). Modelled on Tetra-Expand's DspTests.
#include "dsp_chain.h"

#include <cmath>
#include <cstdio>
#include <vector>

using namespace allmeyn::dsp;

static int failures = 0;
static void check(bool ok, const char* what, double v) {
    std::printf("%s  %-62s (%.3f)\n", ok ? "PASS" : "FAIL", what, v);
    if (!ok) failures++;
}

static const double SR = 48000.0;
static const double PI = 3.14159265358979323846;

/** Stereo sine at [rmsDb] dBFS RMS, [seconds] long. */
static std::vector<float> sine(double hz, double rmsDb, double seconds) {
    const int n = (int) (SR * seconds);
    const double amp = std::pow(10.0, rmsDb / 20.0) * std::sqrt(2.0);
    std::vector<float> v(2 * n);
    for (int i = 0; i < n; i++) v[2 * i] = v[2 * i + 1] = (float) (amp * std::sin(2 * PI * hz * i / SR));
    return v;
}

static double rmsDb(const std::vector<float>& v, double fromSec, double toSec) {
    const int a = (int) (fromSec * SR), b = (int) (toSec * SR);
    double s = 0;
    for (int i = a; i < b; i++) s += (double) v[2 * i] * v[2 * i];
    return 10.0 * std::log10(s / (b - a) + 1e-30);
}

static std::vector<float> run(Params p, std::vector<float> in, int block = 512) {
    Chain c(SR, 2);
    c.setParams(p);
    for (size_t i = 0; i < in.size(); i += 2 * block) {
        const int frames = (int) std::min<size_t>(block, (in.size() - i) / 2);
        c.process(in.data() + i, frames);
    }
    return in;
}

/** Steady-state gain (dB) of a tone through the chain. */
static double gainDb(Params p, double hz, double level) {
    auto in = sine(hz, level, 3.0);
    auto out = run(p, in);
    return rmsDb(out, 2.0, 3.0) - rmsDb(in, 2.0, 3.0);
}

// Analytic LR4 magnitude (dB) of 4-band band b at hz (crossovers 150 / 1k / 6k).
static double bandRespDb(double hz, int b) {
    const double xo[3] = {150, 1000, 6000};
    double mag = 1;
    for (int k = 0; k < 3; k++) {
        const double r4 = std::pow(hz / xo[k], 4.0);
        if (k < b) mag *= r4 / (1 + r4);
        else if (k == b) mag *= 1 / (1 + r4);
    }
    return 20 * std::log10(mag);
}

int main() {
    // 1. Everything off: bit-exact pass-through.
    {
        auto in = sine(1000, -12, 0.5);
        auto out = run(Params {}, in);
        double maxDiff = 0;
        for (size_t i = 0; i < in.size(); i++) maxDiff = std::fmax(maxDiff, std::fabs(out[i] - in[i]));
        check(maxDiff == 0.0, "all processors off: output identical to input", maxDiff);
    }

    // 2. Neutral settings with every processor on: bands sum back flat (allpass only).
    {
        Params p; p.companderOn = p.bassOn = p.impactOn = p.vocalOn = true;
        p.expansionPercent = 0; p.bassPercent = 0; p.impactPercent = 0;
        p.presencePercent = p.detailPercent = p.airPercent = 0;
        double worst = 0;
        for (double hz : {40.0, 150.0, 400.0, 1000.0, 3000.0, 6000.0, 15000.0}) worst = std::fmax(worst, std::fabs(gainDb(p, hz, -20)));
        check(worst < 0.05, "neutral chain is flat 40 Hz - 15 kHz (max |dB|)", worst);
    }

    // 3/4. Expansion and compression law. A tone also leaks into the neighbouring bands, whose
    // (phase-aligned) outputs add back in, so the expected output sums all four bands: each band
    // sees the tone at its filter response and applies ratio x (level - its pivot), clamped.
    const double hz = 387, level = -10;
    auto expected = [&](double percent, double pivot) {
        const double off[4] = {0, -2.89, -3.14, 0};
        double amp = 0;
        for (int b = 0; b < 4; b++) {
            const double resp = bandRespDb(hz, b);
            double g = percent / 100.0 * (level + resp - (pivot + off[b]));
            g = std::fmin(12.0, std::fmax(-40.0, g));
            amp += std::pow(10.0, (resp + g) / 20.0);
        }
        return 20 * std::log10(amp);
    };
    {
        Params p; p.companderOn = true; p.expansionPercent = 50; p.transitionDb = -20;
        const double got = gainDb(p, hz, level), want = expected(50, -20);
        check(std::fabs(got - want) < 0.3, "expansion 50 %: gain = 0.5 x (level - pivot), all bands", got - want);
    }
    {
        Params p; p.companderOn = true; p.expansionPercent = -50; p.transitionDb = -20;
        const double got = gainDb(p, hz, level), want = expected(-50, -20);
        check(got < -3 && std::fabs(got - want) < 0.3, "compression -50 %: gain = -0.5 x (level - pivot), all bands", got - want);
    }
    // 5. Gain limits: upward capped at +12 dB (compression of a very quiet tone).
    {
        Params p; p.companderOn = true; p.expansionPercent = -50; p.transitionDb = 0;
        const double got = gainDb(p, hz, -60);
        check(got < 12.3 && got > 10.5, "upward gain capped at +12 dB", got);
    }
    // 6. HF Transition only moves the top band.
    {
        Params a; a.companderOn = true; a.expansionPercent = 50;
        Params b = a; b.hfTransitionDb = 10;
        const double hfShift = gainDb(a, 12000, -20) - gainDb(b, 12000, -20);
        const double midShift = gainDb(a, hz, -20) - gainDb(b, hz, -20);
        check(std::fabs(hfShift - 5.0) < 0.5 && std::fabs(midShift) < 0.1, "HF Transition +10 dB lowers HF gain by 5 dB only", hfShift);
    }

    // 7. Bass Restoration: 100 % adds ratio +0.5 below 150 Hz around its own pivot; mids untouched.
    {
        Params p; p.bassOn = true; p.bassPercent = 100; p.bassTransitionDb = -20;
        const double got = gainDb(p, 50, -10);
        check(std::fabs(got - 0.5 * (-10 - (-20))) < 0.6, "bass 100 %: 50 Hz at -10 dBFS gets +5 dB", got);
        const double quiet = gainDb(p, 50, -30);
        check(std::fabs(quiet - (-5.0)) < 0.6, "bass 100 %: 50 Hz at -30 dBFS gets -5 dB", quiet);
        check(std::fabs(gainDb(p, 2000, -10)) < 0.1, "bass restoration leaves 2 kHz alone", gainDb(p, 2000, -10));
    }

    // 8. Impact Restoration: no boost on steady tones, a boost on sudden attacks, capped.
    {
        Params p; p.impactOn = true; p.impactPercent = 100;
        check(std::fabs(gainDb(p, 1000, -20)) < 0.3, "impact: steady tone gets no boost", gainDb(p, 1000, -20));
        // silence then a burst: compare the burst's first 10 ms with the input
        auto in = sine(1000, -20, 1.0);
        for (int i = 0; i < (int) (0.5 * SR); i++) in[2 * i] = in[2 * i + 1] = 0;
        Chain c(SR, 2); c.setParams(p);
        auto out = in; c.process(out.data(), (int) (out.size() / 2));
        const double boost = rmsDb(out, 0.501, 0.511) - rmsDb(in, 0.501, 0.511);
        // (the gain itself is capped at 12 dB, see the meter; a 10 ms window at the onset also
        // catches the filters' attack ringing, so allow a little over)
        check(boost > 3 && boost < 12.5, "impact 100 %: attack boosted (dB, about 12 max)", boost);
        double maxImp = 0;
        for (int b = 0; b < kBands; b++) maxImp = std::fmax(maxImp, c.meters().impactDb[b]);
        check(maxImp <= 12.0f, "impact meter never exceeds +12 dB", maxImp);
        Params half = p; half.impactPercent = 50;
        Chain c2(SR, 2); c2.setParams(half);
        auto out2 = in; c2.process(out2.data(), (int) (out2.size() / 2));
        const double boost2 = rmsDb(out2, 0.501, 0.511) - rmsDb(in, 0.501, 0.511);
        check(boost2 <= 6.5 && boost2 < boost - 4, "impact 50 %: boost about +6 dB at most", boost2);
    }

    // 9. Switching a processor on mid-tone doesn't click (crossfaded).
    {
        auto in = sine(1000, -12, 1.0);
        Chain c(SR, 2);
        Params p; c.setParams(p);
        auto out = in;
        const int half = (int) (out.size() / 4);
        c.process(out.data(), half);
        p.companderOn = true; p.expansionPercent = 50; p.transitionDb = -40;  // +big gain once on
        c.setParams(p);
        c.process(out.data() + 2 * half, (int) (out.size() / 2) - half);
        double maxStep = 0, steady = 0;
        for (size_t i = 2; i < out.size(); i += 2) {
            const double d = std::fabs(out[i] - out[i - 2]);
            if ((int) (i / 2) > half - 100 && (int) (i / 2) < half + 4800) maxStep = std::fmax(maxStep, d);
            if ((int) (i / 2) > (int) (0.9 * SR)) steady = std::fmax(steady, d);
        }
        check(maxStep <= steady * 1.05, "turning the compander on: no step bigger than steady state", maxStep / steady);
    }

    // 10. Order: a valid permutation is used, an invalid one ignored.
    {
        Params a; a.companderOn = a.impactOn = true; a.expansionPercent = 50; a.impactPercent = 100;
        auto burst = sine(200, -15, 1.0);
        for (int i = 0; i < (int) (0.5 * SR); i++) burst[2 * i] = burst[2 * i + 1] = 0;
        Params b = a; b.order[0] = kImpact; b.order[1] = kBass; b.order[2] = kCompander; b.order[3] = kVocal;
        auto x = run(a, burst), y = run(b, burst);
        double diff = 0;
        for (size_t i = 0; i < x.size(); i++) diff = std::fmax(diff, std::fabs(x[i] - y[i]));
        check(diff > 1e-4, "changing the processor order changes the result", diff);
        Params bad = a; bad.order[0] = kBass; bad.order[1] = kBass; bad.order[2] = kBass; bad.order[3] = kBass;
        auto z = run(bad, burst);
        double same = 0;
        for (size_t i = 0; i < x.size(); i++) same = std::fmax(same, std::fabs(x[i] - z[i]));
        check(same == 0.0, "an invalid order is ignored (default order kept)", same);
    }

    // 11. Volume and Soft Clip.
    {
        Params p; p.volumeDb = -6;
        check(std::fabs(gainDb(p, 1000, -20) + 6) < 0.01, "volume -6 dB", gainDb(p, 1000, -20));
        Params c; c.volumeDb = 10; c.softClip = true;
        auto out = run(c, sine(1000, -3, 0.5));
        float peak = 0;
        for (float v : out) peak = std::fmax(peak, std::fabs(v));
        check(peak <= 1.0f, "soft clip keeps peaks at or below 0 dBFS", peak);
    }

    // 12. Mono input works.
    {
        Params p; p.companderOn = true; p.expansionPercent = 50;
        const int n = (int) (SR * 2);
        std::vector<float> mono(n);
        for (int i = 0; i < n; i++) mono[i] = (float) (0.3 * std::sin(2 * PI * hz * i / SR));
        Chain c(SR, 1); c.setParams(p);
        c.process(mono.data(), n);
        bool finite = true;
        for (float v : mono) finite = finite && std::isfinite(v);
        check(finite, "mono: processes and stays finite", 0);
    }

    // 13. High sample rate (DSD -> PCM 176.4 kHz, 768 kHz PCM): stable and flat.
    for (double rate : {176400.0, 768000.0}) {
        Params p; p.companderOn = p.bassOn = p.impactOn = p.vocalOn = true;
        p.expansionPercent = 0; p.bassPercent = 0; p.impactPercent = 0;
        p.presencePercent = p.detailPercent = p.airPercent = 0;
        Chain c(rate, 2); c.setParams(p);
        const int n = (int) rate;
        std::vector<float> v(2 * n);
        for (int i = 0; i < n; i++) v[2 * i] = v[2 * i + 1] = (float) (0.1 * std::sin(2 * PI * 1000 * i / rate));
        c.process(v.data(), n);
        double s = 0;
        for (int i = n / 2; i < n; i++) s += (double) v[2 * i] * v[2 * i];
        const double db = 10 * std::log10(s / (n / 2)) - 20 * std::log10(0.1 / std::sqrt(2.0));
        char label[80];
        std::snprintf(label, sizeof label, "neutral chain flat at %.1f kHz (dB)", rate / 1000);
        check(std::fabs(db) < 0.05, label, db);
    }

    // 14. Vocal & Detail Restoration.
    {
        // Neutral (all 0 %) is flat and effectively untouched.
        Params n; n.vocalOn = true; n.presencePercent = n.detailPercent = n.airPercent = 0;
        double worst = 0;
        for (double f : {100.0, 1000.0, 3000.0, 12000.0}) worst = std::fmax(worst, std::fabs(gainDb(n, f, -20)));
        check(worst < 0.001, "vocal: all 0 % is flat (max |dB|)", worst);
        auto in = sine(1000, -12, 0.5);
        auto out = run(n, in);
        double maxDiff = 0;
        for (size_t i = 0; i < in.size(); i++) maxDiff = std::fmax(maxDiff, std::fabs(out[i] - in[i]));
        check(maxDiff < 1e-6, "vocal: all 0 % output equals input (max diff)", maxDiff);
    }
    {
        // Side-only content (L = -R) is never touched: the processor works on the mid signal.
        Params p; p.vocalOn = true; p.presencePercent = p.detailPercent = p.airPercent = 100;
        auto in = sine(2000, -20, 0.5);
        for (size_t i = 0; i < in.size(); i += 2) in[i + 1] = -in[i];
        auto out = run(p, in);
        double maxDiff = 0;
        for (size_t i = 0; i < in.size(); i++) maxDiff = std::fmax(maxDiff, std::fabs(out[i] - in[i]));
        check(maxDiff < 1e-6, "vocal: side-only signal untouched at 100 % (max diff)", maxDiff);
    }
    {
        // Detail lifts a quiet vocal-band tone, leaves a loud one alone, and ignores bass.
        Params p; p.vocalOn = true; p.presencePercent = 0; p.detailPercent = 100; p.airPercent = 0;
        const double quiet = gainDb(p, 1000, -40), loud = gainDb(p, 1000, -6), bass = gainDb(p, 60, -40);
        check(quiet > 1.0 && quiet < 3.3, "vocal detail 100 %: quiet 1 kHz lifted (dB)", quiet);
        check(std::fabs(loud) < 0.05, "vocal detail 100 %: loud 1 kHz unchanged (dB)", loud);
        check(bass < quiet - 0.5, "vocal detail 100 %: 60 Hz lifted less than 1 kHz (dB)", bass);
    }
    {
        // Presence lifts a burst's attack more than its steady part.
        Params p; p.vocalOn = true; p.presencePercent = 100; p.detailPercent = 0; p.airPercent = 0;
        auto in = sine(1500, -20, 1.0);
        for (int i = 0; i < (int) (0.5 * SR); i++) in[2 * i] = in[2 * i + 1] = 0;
        auto out = run(p, in);
        const double onset = rmsDb(out, 0.502, 0.512) - rmsDb(in, 0.502, 0.512);
        const double steady = rmsDb(out, 0.9, 1.0) - rmsDb(in, 0.9, 1.0);
        check(onset > steady + 0.5, "vocal presence 100 %: attack lifted more than steady tone (dB)", onset - steady);
    }
    {
        // Air lifts 12 kHz and leaves 200 Hz (nearly) alone.
        Params p; p.vocalOn = true; p.presencePercent = 0; p.detailPercent = 0; p.airPercent = 100;
        const double hi = gainDb(p, 12000, -20), lo = gainDb(p, 200, -20);
        check(hi > 2.0, "vocal air 100 %: 12 kHz lifted (dB)", hi);
        check(std::fabs(lo) < 0.3, "vocal air 100 %: 200 Hz nearly unchanged (dB)", lo);
    }
    {
        // Air at 44.1 kHz: a loud 15 kHz tone's 3rd harmonic (45 kHz) would fold to 900 Hz without
        // oversampling. Measure what lands at 900 Hz relative to the tone.
        const double sr = 44100.0;
        Params p; p.vocalOn = true; p.presencePercent = 0; p.detailPercent = 0; p.airPercent = 100;
        Chain c(sr, 2); c.setParams(p);
        const int n = (int) sr;
        std::vector<float> v(2 * n);
        for (int i = 0; i < n; i++) v[2 * i] = v[2 * i + 1] = (float) (0.5 * std::sin(2 * PI * 15000 * i / sr));
        c.process(v.data(), n);
        auto goertzel = [&](double f) {
            double re = 0, im = 0;
            for (int i = n / 2; i < n; i++) { re += v[2 * i] * std::cos(2 * PI * f * i / sr); im += v[2 * i] * std::sin(2 * PI * f * i / sr); }
            return 20 * std::log10(std::sqrt(re * re + im * im) / (n / 4) + 1e-12);
        };
        const double alias = goertzel(900) - goertzel(15000);
        check(alias < -40, "vocal air at 44.1 kHz: folded 3rd harmonic below -40 dB", alias);
    }
    {
        // Turning it on mid-tone doesn't click.
        auto in = sine(1000, -12, 1.0);
        Chain c(SR, 2);
        Params p; c.setParams(p);
        auto out = in;
        const int half = (int) (out.size() / 4);
        c.process(out.data(), half);
        p.vocalOn = true; p.presencePercent = p.detailPercent = p.airPercent = 100;
        c.setParams(p);
        c.process(out.data() + 2 * half, (int) (out.size() / 2) - half);
        double maxStep = 0, steady = 0;
        for (size_t i = 2; i < out.size(); i += 2) {
            const double d = std::fabs(out[i] - out[i - 2]);
            if ((int) (i / 2) > half - 100 && (int) (i / 2) < half + 4800) maxStep = std::fmax(maxStep, d);
            if ((int) (i / 2) > (int) (0.9 * SR)) steady = std::fmax(steady, d);
        }
        check(maxStep <= steady * 1.05, "turning vocal restoration on: no step bigger than steady state", maxStep / steady);
    }
    // Stable and finite from 44.1 kHz to 768 kHz, mono too.
    for (double rate : {44100.0, 768000.0}) {
        for (int ch : {1, 2}) {
            Params p; p.vocalOn = true; p.presencePercent = p.detailPercent = p.airPercent = 100;
            Chain c(rate, ch); c.setParams(p);
            const int n = (int) rate;
            std::vector<float> v((size_t) ch * n);
            for (int i = 0; i < n; i++)
                for (int k = 0; k < ch; k++) v[(size_t) ch * i + k] = (float) (0.5 * std::sin(2 * PI * (300 + 9000 * (i % 997) / 997.0) * i / rate));
            c.process(v.data(), n);
            bool ok = true;
            for (float x : v) ok = ok && std::isfinite(x) && std::fabs(x) < 4.0f;
            char label[80];
            std::snprintf(label, sizeof label, "vocal 100 %% stable at %.1f kHz, %d ch", rate / 1000, ch);
            check(ok, label, 0);
        }
    }

    std::printf(failures ? "%d DSP test(s) FAILED\n" : "DSP tests passed\n", failures);
    return failures ? 1 : 0;
}
