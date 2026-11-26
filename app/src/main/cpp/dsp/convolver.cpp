#include "convolver.h"

#include <algorithm>
#include <cmath>

namespace allmeyn::dsp {

namespace {
constexpr double kPi = 3.14159265358979323846;
using cdouble = std::complex<double>;

int nextPow2(int n) {
    int p = 1;
    while (p < n) p <<= 1;
    return p;
}

/** Double-precision FFT for filter design (offline; sizes up to a few hundred thousand). */
void fftDouble(std::vector<cdouble>& a, bool inverse) {
    const int n = (int) a.size();
    for (int i = 1, j = 0; i < n; i++) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[(size_t) i], a[(size_t) j]);
    }
    for (int len = 2; len <= n; len <<= 1) {
        const double ang = 2 * kPi / len * (inverse ? 1 : -1);
        const cdouble wl(std::cos(ang), std::sin(ang));
        for (int i = 0; i < n; i += len) {
            cdouble w(1);
            for (int k = 0; k < len / 2; k++) {
                const cdouble u = a[(size_t) (i + k)], v = a[(size_t) (i + k + len / 2)] * w;
                a[(size_t) (i + k)] = u + v;
                a[(size_t) (i + k + len / 2)] = u - v;
                w *= wl;
            }
        }
    }
    if (inverse) for (auto& x : a) x /= n;
}

/** [ir] (at [from] Hz) at [to] Hz: windowed-sinc interpolation, gain kept (sum scaled by from/to). */
std::vector<float> resample(const std::vector<float>& ir, double from, double to) {
    if (std::fabs(from - to) < 0.5) return ir;
    const double ratio = to / from;              // output samples per input sample
    const double fc = std::min(1.0, ratio) * 0.97;  // cutoff, fraction of the input's Nyquist
    const double half = 32.0 / fc;               // half-width in input samples
    // A response that starts at full size (minimum phase) rings a little before its first sample
    // once band-limited: start [lead] output samples early so none of that is cut off.
    const size_t lead = (size_t) std::ceil(half * ratio);
    const size_t outLen = (size_t) std::ceil(ir.size() * ratio) + 2 * lead;
    std::vector<float> out(outLen);
    for (size_t m = 0; m < outLen; m++) {
        const double s = ((double) m - (double) lead) / ratio;  // position in input samples
        const long a = (long) std::ceil(s - half), b = (long) std::floor(s + half);
        double acc = 0;
        for (long k = std::max(0L, a); k <= std::min((long) ir.size() - 1, b); k++) {
            const double d = s - k;
            const double x = fc * d;
            const double sinc = std::fabs(x) < 1e-12 ? 1.0 : std::sin(kPi * x) / (kPi * x);
            const double w = 0.5 + 0.5 * std::cos(kPi * d / half);  // Hann
            acc += ir[(size_t) k] * fc * sinc * w;
        }
        out[m] = (float) (acc / ratio);
    }
    return out;
}

/** Minimum-phase FIR whose magnitude follows GraphicEQ points (dB interpolated on log frequency). */
std::vector<float> designGraphic(const std::vector<float>& hz, const std::vector<float>& db, double fs) {
    const int taps = (int) std::lround(fs * 0.1);           // 100 ms, as AutoEq's FIRs
    const int n = nextPow2(taps * 4);                        // design grid (fine low-frequency detail)
    std::vector<cdouble> a((size_t) n);
    auto dbAt = [&](double f) {
        if (f <= hz.front()) return (double) db.front();
        if (f >= hz.back()) return (double) db.back();
        const size_t i = (size_t) (std::upper_bound(hz.begin(), hz.end(), (float) f) - hz.begin());
        const double f0 = hz[i - 1], f1 = hz[i];
        const double t = (std::log(f) - std::log(f0)) / (std::log(f1) - std::log(f0));
        return db[i - 1] + t * (db[i] - db[i - 1]);
    };
    for (int k = 0; k <= n / 2; k++) {
        const double f = std::max(1.0, k * fs / n);
        const double lnMag = dbAt(f) / 20.0 * std::log(10.0);
        a[(size_t) k] = lnMag;
        if (k > 0 && k < n / 2) a[(size_t) (n - k)] = lnMag;
    }
    // Real cepstrum -> fold (causal) -> exp -> minimum-phase spectrum.
    fftDouble(a, true);
    for (int i = 1; i < n / 2; i++) a[(size_t) i] *= 2.0;
    for (int i = n / 2 + 1; i < n; i++) a[(size_t) i] = 0.0;
    fftDouble(a, false);
    for (auto& x : a) x = std::exp(x);
    fftDouble(a, true);
    std::vector<float> h((size_t) taps);
    const int fade = taps / 10;
    for (int i = 0; i < taps; i++) {
        double v = a[(size_t) i].real();
        if (i >= taps - fade) v *= 0.5 + 0.5 * std::cos(kPi * (i - (taps - fade)) / fade);
        h[(size_t) i] = (float) v;
    }
    return h;
}
}  // namespace

void Fft::setup(int n) {
    n_ = n;
    rev_.assign((size_t) std::max(n, 1), 0);
    twiddle_.resize((size_t) std::max(n / 2, 1));
    if (n <= 1) return;
    int bits = 0;
    while ((1 << bits) < n) bits++;
    for (int i = 0; i < n; i++) {
        int r = 0;
        for (int b = 0; b < bits; b++) if (i & (1 << b)) r |= 1 << (bits - 1 - b);
        rev_[(size_t) i] = r;
    }
    for (int k = 0; k < n / 2; k++) {
        const double ang = -2 * kPi * k / n;
        twiddle_[(size_t) k] = cfloat((float) std::cos(ang), (float) std::sin(ang));
    }
}

void Fft::transform(cfloat* a, bool inverse) const {
    const int n = n_;
    for (int i = 0; i < n; i++) if (i < rev_[(size_t) i]) std::swap(a[i], a[rev_[(size_t) i]]);
    for (int len = 2; len <= n; len <<= 1) {
        const int half = len / 2, step = n / len;
        for (int i = 0; i < n; i += len) {
            for (int k = 0; k < half; k++) {
                cfloat w = twiddle_[(size_t) (k * step)];
                if (inverse) w = std::conj(w);
                const cfloat u = a[i + k], v = a[i + k + half] * w;
                a[i + k] = u + v;
                a[i + k + half] = u - v;
            }
        }
    }
}

int convBlockSize(double fs) { return std::clamp(nextPow2((int) (fs * 0.005)), 64, 2048); }

std::vector<std::vector<float>> designConvFilter(const ConvSource& s, double fs) {
    std::vector<std::vector<float>> out;
    if (s.irChannels > 0 && s.irRate > 0) {
        for (int c = 0; c < std::min(2, s.irChannels); c++) out.push_back(resample(s.ir[c], s.irRate, fs));
    } else if (!s.graphicHz.empty() && s.graphicHz.size() == s.graphicDb.size()) {
        out.push_back(designGraphic(s.graphicHz, s.graphicDb, fs));
    }
    const size_t maxTaps = (size_t) (fs * kMaxConvSeconds);
    const float g = (float) std::pow(10.0, s.gainDb / 20.0);
    for (auto& h : out) {
        if (h.size() > maxTaps) {
            h.resize(maxTaps);
            const size_t fade = maxTaps / 20;
            for (size_t i = 0; i < fade; i++) h[maxTaps - fade + i] *= (float) (0.5 + 0.5 * std::cos(kPi * i / fade));
        }
        for (auto& v : h) v *= g;
    }
    return out;
}

class ConvKernel {
public:
    double rate = 0;
    int B = 0, N = 0, P = 0, taps = 0;
    int kernels = 1;                        // 1: same filter for both channels
    Fft fft;
    std::vector<cfloat> H[2];               // P partitions x (B+1) bins
    std::vector<cfloat> fdl[2];             // P input spectra x (B+1) bins (ring)
    int slot = 0;
    std::vector<float> inHist[2];           // last 2B input samples (overlap-save)
    std::vector<float> outBlock[2];         // B output samples being played
    int pos = 0;
    std::vector<cfloat> work, acc[2];

    void processBlock() {
        const int n = N;
        // Both channels in one complex FFT: left real, right imaginary.
        for (int i = 0; i < n; i++) work[(size_t) i] = cfloat(inHist[0][(size_t) i], inHist[1][(size_t) i]);
        fft.transform(work.data(), false);
        cfloat* x0 = &fdl[0][(size_t) slot * (B + 1)];
        cfloat* x1 = &fdl[1][(size_t) slot * (B + 1)];
        for (int k = 0; k <= B; k++) {
            const cfloat z = work[(size_t) k], zc = std::conj(work[(size_t) ((n - k) & (n - 1))]);
            x0[k] = 0.5f * (z + zc);
            x1[k] = cfloat(0, -0.5f) * (z - zc);
        }
        for (int c = 0; c < 2; c++) {
            const cfloat* h = H[kernels == 2 ? c : 0].data();
            cfloat* y = acc[c].data();
            std::fill(y, y + B + 1, cfloat(0));
            for (int p = 0; p < P; p++) {
                const cfloat* xs = &fdl[c][(size_t) (((slot - p + P) % P) * (B + 1))];
                const cfloat* hp = h + (size_t) p * (B + 1);
                for (int k = 0; k <= B; k++) y[k] += hp[k] * xs[k];
            }
        }
        slot = (slot + 1) % P;
        // Back to one complex signal (left real, right imaginary): both outputs are real.
        for (int k = 0; k <= B; k++) work[(size_t) k] = acc[0][(size_t) k] + cfloat(0, 1) * acc[1][(size_t) k];
        for (int k = B + 1; k < n; k++) work[(size_t) k] = std::conj(acc[0][(size_t) (n - k)]) + cfloat(0, 1) * std::conj(acc[1][(size_t) (n - k)]);
        fft.transform(work.data(), true);
        const float scale = 1.0f / n;
        for (int i = 0; i < B; i++) {
            outBlock[0][(size_t) i] = work[(size_t) (B + i)].real() * scale;
            outBlock[1][(size_t) i] = work[(size_t) (B + i)].imag() * scale;
        }
        // Slide the input window by one block.
        for (int c = 0; c < 2; c++) std::copy(inHist[c].begin() + B, inHist[c].end(), inHist[c].begin());
    }
};

ConvKernel* buildConvKernel(const ConvSource& source, double fs) {
    const auto filters = designConvFilter(source, fs);
    if (filters.empty() || filters[0].empty()) return nullptr;
    auto* k = new ConvKernel();
    k->rate = fs;
    k->B = convBlockSize(fs);
    k->N = 2 * k->B;
    k->kernels = (int) filters.size();
    size_t taps = 0;
    for (auto& f : filters) taps = std::max(taps, f.size());
    k->taps = (int) taps;
    k->P = (int) ((taps + k->B - 1) / k->B);
    k->fft.setup(k->N);
    std::vector<cfloat> buf((size_t) k->N);
    for (int c = 0; c < k->kernels; c++) {
        k->H[c].assign((size_t) k->P * (k->B + 1), cfloat(0));
        for (int p = 0; p < k->P; p++) {
            std::fill(buf.begin(), buf.end(), cfloat(0));
            for (int i = 0; i < k->B; i++) {
                const size_t t = (size_t) p * k->B + i;
                if (t < filters[(size_t) c].size()) buf[(size_t) i] = filters[(size_t) c][t];
            }
            k->fft.transform(buf.data(), false);
            std::copy(buf.begin(), buf.begin() + k->B + 1, k->H[c].begin() + (size_t) p * (k->B + 1));
        }
    }
    for (int c = 0; c < 2; c++) {
        k->fdl[c].assign((size_t) k->P * (k->B + 1), cfloat(0));
        k->inHist[c].assign((size_t) k->N, 0.0f);
        k->outBlock[c].assign((size_t) k->B, 0.0f);
        k->acc[c].assign((size_t) k->B + 1, cfloat(0));
    }
    k->work.assign((size_t) k->N, cfloat(0));
    return k;
}

void freeConvKernel(ConvKernel* k) { delete k; }
double convKernelRate(const ConvKernel* k) { return k ? k->rate : 0; }
int convKernelLatency(const ConvKernel* k) { return k ? k->B : 0; }
int convKernelTaps(const ConvKernel* k) { return k ? k->taps : 0; }

void convProcessFrame(ConvKernel* k, float* x, int channels) {
    const int i = k->pos;
    k->inHist[0][(size_t) (k->B + i)] = x[0];
    k->inHist[1][(size_t) (k->B + i)] = channels > 1 ? x[1] : 0.0f;
    x[0] = k->outBlock[0][(size_t) i];
    if (channels > 1) x[1] = k->outBlock[1][(size_t) i];
    if (++k->pos == k->B) {
        k->pos = 0;
        k->processBlock();
    }
}

void convReset(ConvKernel* k) {
    for (int c = 0; c < 2; c++) {
        std::fill(k->fdl[c].begin(), k->fdl[c].end(), cfloat(0));
        std::fill(k->inHist[c].begin(), k->inHist[c].end(), 0.0f);
        std::fill(k->outBlock[c].begin(), k->outBlock[c].end(), 0.0f);
    }
    k->pos = 0;
    k->slot = 0;
}

}  // namespace allmeyn::dsp
