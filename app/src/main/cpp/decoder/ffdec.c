// APE (Monkey's Audio) and WavPack decoding through a minimal FFmpeg build (LGPL).
// Reads the file with pread through a custom AVIOContext, so FFmpeg can seek freely.
// Output keeps the source bit depth: 16-bit, 24-bit (packed), 32-bit, or float.

#include "decoder.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef HAVE_FFMPEG

Decoder *ff_open(int fd, char *err, int err_len) {
    (void) fd;
    uint8_t m[4];
    if (pread(fd, m, 4, 0) == 4 && (!memcmp(m, "MAC ", 4) || !memcmp(m, "wvpk", 4))) {
        snprintf(err, err_len, "APE/WavPack support isn't built into this app (FFmpeg missing)");
    }
    return NULL;
}

#else

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>

#define IO_BUF 65536

typedef struct {
    Decoder base;
    int fd;
    int64_t size, io_pos;
    AVIOContext *avio;
    AVFormatContext *fmt;
    AVCodecContext *cc;
    AVPacket *pkt;
    AVFrame *frame;
    int stream;
    AVRational tb;
    int out_bytes;  // bytes per sample in the output format

    uint8_t *pend;  // decoded, converted, not yet returned
    int pend_frames, pend_off, pend_cap;
    int64_t next_index;  // frame index of the next decoded sample if pts is missing
    int64_t skip_until;  // drop decoded frames before this index (after a seek)
    int draining, done;
} Ff;

static int io_read(void *opaque, uint8_t *buf, int n) {
    Ff *f = opaque;
    ssize_t r = pread(f->fd, buf, (size_t) n, (off_t) f->io_pos);
    if (r <= 0) return AVERROR_EOF;
    f->io_pos += r;
    return (int) r;
}

static int64_t io_seek(void *opaque, int64_t off, int whence) {
    Ff *f = opaque;
    switch (whence & ~AVSEEK_FORCE) {
        case AVSEEK_SIZE: return f->size;
        case SEEK_SET: f->io_pos = off; break;
        case SEEK_CUR: f->io_pos += off; break;
        case SEEK_END: f->io_pos = f->size + off; break;
        default: return -1;
    }
    return f->io_pos;
}

static int32_t sample_s32(const AVFrame *fr, int planar, int ch, int i, int nch) {
    switch (fr->format) {
        case AV_SAMPLE_FMT_U8:
        case AV_SAMPLE_FMT_U8P: {
            const uint8_t *p = planar ? fr->extended_data[ch] : fr->extended_data[0];
            return ((int32_t) p[planar ? i : i * nch + ch] - 128) << 24;
        }
        case AV_SAMPLE_FMT_S16:
        case AV_SAMPLE_FMT_S16P: {
            const int16_t *p = (const int16_t *) (planar ? fr->extended_data[ch] : fr->extended_data[0]);
            return (int32_t) ((uint32_t) (int32_t) p[planar ? i : i * nch + ch] << 16);
        }
        default: {
            const int32_t *p = (const int32_t *) (planar ? fr->extended_data[ch] : fr->extended_data[0]);
            return p[planar ? i : i * nch + ch];
        }
    }
}

/** Appends frame samples [from, nb) to the pending buffer in the output format. */
static int append(Ff *f, const AVFrame *fr, int from) {
    int nch = f->base.info.channels, n = fr->nb_samples - from;
    if (n <= 0) return 0;
    int need = (f->pend_frames + n) * nch * f->out_bytes;
    if (need > f->pend_cap) {
        uint8_t *p = realloc(f->pend, (size_t) need);
        if (!p) return -1;
        f->pend = p;
        f->pend_cap = need;
    }
    int planar = av_sample_fmt_is_planar(fr->format);
    uint8_t *o = f->pend + f->pend_frames * nch * f->out_bytes;
    int fmt = f->base.info.format;
    for (int i = from; i < fr->nb_samples; i++) {
        for (int c = 0; c < nch; c++) {
            if (fmt == DEC_F32) {
                float v;
                if (fr->format == AV_SAMPLE_FMT_DBL || fr->format == AV_SAMPLE_FMT_DBLP) {
                    const double *p = (const double *) (planar ? fr->extended_data[c] : fr->extended_data[0]);
                    v = (float) p[planar ? i : i * nch + c];
                } else {
                    const float *p = (const float *) (planar ? fr->extended_data[c] : fr->extended_data[0]);
                    v = p[planar ? i : i * nch + c];
                }
                memcpy(o, &v, 4);
                o += 4;
            } else {
                uint32_t u = (uint32_t) sample_s32(fr, planar, c, i, nch);
                if (fmt == DEC_S16) {
                    o[0] = (uint8_t) (u >> 16); o[1] = (uint8_t) (u >> 24); o += 2;
                } else if (fmt == DEC_S24) {
                    o[0] = (uint8_t) (u >> 8); o[1] = (uint8_t) (u >> 16); o[2] = (uint8_t) (u >> 24); o += 3;
                } else {
                    o[0] = (uint8_t) u; o[1] = (uint8_t) (u >> 8); o[2] = (uint8_t) (u >> 16); o[3] = (uint8_t) (u >> 24); o += 4;
                }
            }
        }
    }
    f->pend_frames += n;
    return 0;
}

/** Decodes until something is pending or the stream ends. 0 ok, 1 end, <0 error. */
static int decode_more(Ff *f) {
    while (!f->done) {
        int rc = avcodec_receive_frame(f->cc, f->frame);
        if (rc == 0) {
            int64_t start = f->frame->pts != AV_NOPTS_VALUE
                ? av_rescale_q(f->frame->pts, f->tb, (AVRational) {1, f->base.info.sample_rate})
                : f->next_index;
            f->next_index = start + f->frame->nb_samples;
            int from = 0;
            if (start < f->skip_until) {
                int64_t skip = f->skip_until - start;
                from = skip >= f->frame->nb_samples ? f->frame->nb_samples : (int) skip;
            }
            rc = append(f, f->frame, from);
            av_frame_unref(f->frame);
            if (rc) return rc;
            if (f->pend_frames > 0) return 0;
            continue;
        }
        if (rc == AVERROR_EOF) {
            f->done = 1;
            return 1;
        }
        if (rc != AVERROR(EAGAIN)) return rc;
        if (f->draining) {
            f->done = 1;
            return 1;
        }
        // Needs input.
        rc = av_read_frame(f->fmt, f->pkt);
        if (rc < 0) {
            f->draining = 1;
            avcodec_send_packet(f->cc, NULL);
            continue;
        }
        if (f->pkt->stream_index == f->stream) {
            rc = avcodec_send_packet(f->cc, f->pkt);
            if (rc < 0 && rc != AVERROR(EAGAIN)) {
                av_packet_unref(f->pkt);
                return rc;
            }
        }
        av_packet_unref(f->pkt);
    }
    return 1;
}

static int ff_read(Decoder *base, uint8_t *out, int max_frames) {
    Ff *f = (Ff *) base;
    int written = 0, fb = f->base.info.channels * f->out_bytes;
    while (written < max_frames) {
        if (f->pend_off >= f->pend_frames) {
            f->pend_off = f->pend_frames = 0;
            int rc = decode_more(f);
            if (rc < 0) return written > 0 ? written : -1;
            if (rc == 1 && f->pend_frames == 0) break;
        }
        int n = f->pend_frames - f->pend_off;
        if (n > max_frames - written) n = max_frames - written;
        memcpy(out + written * fb, f->pend + f->pend_off * fb, (size_t) n * fb);
        f->pend_off += n;
        written += n;
    }
    return written;
}

static int ff_seek(Decoder *base, int64_t frame) {
    Ff *f = (Ff *) base;
    if (frame < 0) frame = 0;
    int64_t ts = av_rescale_q(frame, (AVRational) {1, f->base.info.sample_rate}, f->tb);
    if (av_seek_frame(f->fmt, f->stream, ts, AVSEEK_FLAG_BACKWARD) < 0 &&
        av_seek_frame(f->fmt, f->stream, 0, AVSEEK_FLAG_BACKWARD) < 0) {
        return -1;
    }
    avcodec_flush_buffers(f->cc);
    f->pend_frames = f->pend_off = 0;
    f->skip_until = frame;
    f->next_index = 0;
    f->draining = f->done = 0;
    return 0;
}

static void ff_close(Decoder *base) {
    Ff *f = (Ff *) base;
    av_frame_free(&f->frame);
    av_packet_free(&f->pkt);
    avcodec_free_context(&f->cc);
    if (f->fmt) avformat_close_input(&f->fmt);
    if (f->avio) {
        av_freep(&f->avio->buffer);
        avio_context_free(&f->avio);
    }
    free(f->pend);
    close(f->fd);
    free(f);
}

Decoder *ff_open(int fd, char *err, int err_len) {
    uint8_t m[4];
    if (pread(fd, m, 4, 0) != 4) return NULL;
    int is_ape = !memcmp(m, "MAC ", 4), is_wv = !memcmp(m, "wvpk", 4);
    if (!is_ape && !is_wv) return NULL;

    av_log_set_level(AV_LOG_ERROR);
    Ff *f = calloc(1, sizeof(Ff));
    if (!f) return NULL;
    f->fd = dup(fd);
    struct stat st;
    f->size = fstat(f->fd, &st) == 0 ? st.st_size : -1;
    f->base.read = ff_read;
    f->base.seek = ff_seek;
    f->base.close = ff_close;

    uint8_t *iobuf = av_malloc(IO_BUF);
    f->avio = iobuf ? avio_alloc_context(iobuf, IO_BUF, 0, f, io_read, NULL, io_seek) : NULL;
    f->fmt = avformat_alloc_context();
    if (!f->avio || !f->fmt) {
        if (!f->avio) av_free(iobuf);
        goto fail;
    }
    f->fmt->pb = f->avio;
    f->fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    if (avformat_open_input(&f->fmt, NULL, NULL, NULL) < 0) {
        f->fmt = NULL;  // freed by avformat_open_input on failure
        snprintf(err, err_len, "%s: can't open container", is_ape ? "APE" : "WavPack");
        goto fail;
    }
    avformat_find_stream_info(f->fmt, NULL);
    f->stream = av_find_best_stream(f->fmt, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (f->stream < 0) goto fail;
    AVStream *s = f->fmt->streams[f->stream];
    const AVCodec *codec = avcodec_find_decoder(s->codecpar->codec_id);
    if (!codec) goto fail;
    f->cc = avcodec_alloc_context3(codec);
    if (!f->cc || avcodec_parameters_to_context(f->cc, s->codecpar) < 0 || avcodec_open2(f->cc, codec, NULL) < 0) {
        snprintf(err, err_len, "%s: decoder failed to start", is_ape ? "APE" : "WavPack");
        goto fail;
    }
    f->pkt = av_packet_alloc();
    f->frame = av_frame_alloc();
    f->tb = s->time_base;

    DecInfo *in = &f->base.info;
    in->sample_rate = f->cc->sample_rate;
    in->channels = f->cc->ch_layout.nb_channels;
    int bits = f->cc->bits_per_raw_sample ? f->cc->bits_per_raw_sample : s->codecpar->bits_per_raw_sample;
    switch (f->cc->sample_fmt) {
        case AV_SAMPLE_FMT_U8: case AV_SAMPLE_FMT_U8P:
        case AV_SAMPLE_FMT_S16: case AV_SAMPLE_FMT_S16P: in->format = DEC_S16; break;
        case AV_SAMPLE_FMT_S32: case AV_SAMPLE_FMT_S32P: in->format = bits > 0 && bits <= 24 ? DEC_S24 : DEC_S32; break;
        case AV_SAMPLE_FMT_FLT: case AV_SAMPLE_FMT_FLTP:
        case AV_SAMPLE_FMT_DBL: case AV_SAMPLE_FMT_DBLP: in->format = DEC_F32; break;
        default:
            snprintf(err, err_len, "unsupported sample format %d", f->cc->sample_fmt);
            goto fail;
    }
    f->out_bytes = dec_sample_bytes(in->format);
    if (s->duration != AV_NOPTS_VALUE && s->duration > 0) {
        in->total_frames = av_rescale_q(s->duration, f->tb, (AVRational) {1, in->sample_rate});
    } else if (f->fmt->duration > 0) {
        in->total_frames = av_rescale(f->fmt->duration, in->sample_rate, AV_TIME_BASE);
    }
    if (in->sample_rate <= 0 || in->channels <= 0 || in->total_frames <= 0) {
        snprintf(err, err_len, "%s: missing rate/channels/length", is_ape ? "APE" : "WavPack");
        goto fail;
    }
    const char *depth = in->format == DEC_F32 ? "float" : in->format == DEC_S16 ? "16-bit" : in->format == DEC_S24 ? "24-bit" : "32-bit";
    snprintf(in->desc, sizeof in->desc, "%s %s %.1f kHz", is_ape ? "APE" : "WavPack", depth, in->sample_rate / 1000.0);
    return &f->base;

fail:
    if (!err[0]) snprintf(err, err_len, "%s: unreadable file", is_ape ? "APE" : "WavPack");
    ff_close(&f->base);
    return NULL;
}

#endif
