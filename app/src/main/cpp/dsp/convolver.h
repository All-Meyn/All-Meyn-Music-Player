// Convolution EQ: a long FIR filter (a headphone correction impulse response, e.g. AutoEq's minimum
// phase WAVs, or one designed from a GraphicEQ) applied with FFT-based convolution.
//
//  - Our own radix-2 FFT (no library).
//  - Uniformly partitioned overlap-save: the filter is cut into blocks of B samples; each block of
//    input costs one FFT + one inverse FFT for both channels together (left in the real part, right
//    in the imaginary part) plus one multiply-add per partition and frequency. Latency B samples,
//    B chosen per sample rate so it's about 5 ms everywhere.
//  - The heavy part (resampling the response to the song's rate, designing a GraphicEQ filter,
//    transforming the partitions) happens in buildConvKernel() on any thread; the audio thread only
//    swaps a ready kernel in (Chain::offerConvolution).
#pragma once

#include <complex>
#include <cstdint>
#include <vector>

namespace allmeyn::dsp {

using cfloat = std::complex<float>;

/** In-place radix-2 FFT of a fixed power-of-two size (twiddles and bit reversal precomputed). */
class Fft {
public:
    explicit Fft(int n = 0) { setup(n); }
    void setup(int n);
    int size() const { return n_; }
    /** Forward (e^-i) transform; [inverse] uses e^+i and does NOT divide by n. */
    void transform(cfloat* data, bool inverse) const;

private:
    int n_ = 0;
    std::vector<int> rev_;
    std::vector<cfloat> twiddle_;  // e^-2πik/n, k < n/2
};

/** What to convolve with. Exactly one of: an impulse response, or GraphicEQ points. */
struct ConvSource {
    std::vector<float> ir[2];   // 1 (both channels) or 2 channels
    int irChannels = 0;
    double irRate = 0;          // the response's own sample rate
    std::vector<float> graphicHz, graphicDb;  // ascending Hz; dB at each
    float gainDb = 0;           // added gain (the source's own level is kept otherwise)
};

constexpr double kMaxConvSeconds = 1.0;  // longer responses are cut (faded out) at 1 s

class ConvKernel;

/**
 * The filter taps for [source] at [sampleRate] (resampled response, or a minimum-phase design from
 * GraphicEQ points), per channel; [channels] gets 1 or 2. Empty if the source is empty.
 */
std::vector<std::vector<float>> designConvFilter(const ConvSource& source, double sampleRate);

/** A kernel ready to run at [sampleRate] (heavy: call off the audio thread). Null if empty. */
ConvKernel* buildConvKernel(const ConvSource& source, double sampleRate);
void freeConvKernel(ConvKernel* k);
double convKernelRate(const ConvKernel* k);
int convKernelLatency(const ConvKernel* k);  // samples (the block size)
int convKernelTaps(const ConvKernel* k);

/** Block size for [sampleRate]: a power of two near 5 ms. */
int convBlockSize(double sampleRate);

/** Runs one frame (x[c], c < channels) through the kernel, in place, B samples late. */
void convProcessFrame(ConvKernel* k, float* x, int channels);
/** Forgets the audio (silence in the pipeline), e.g. after a seek. */
void convReset(ConvKernel* k);

}  // namespace allmeyn::dsp
