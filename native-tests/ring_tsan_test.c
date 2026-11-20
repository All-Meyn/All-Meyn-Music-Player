#include "usb_audio_core.h"
#include <pthread.h>
#include <assert.h>
#include <stdio.h>
static Ring r; static const int N = 2000000;
static void *prod(void *a) { (void)a; int32_t next = 0;
  while (next < N) { int32_t buf[64]; int k = 0; while (k < 64 && next + k < N) { buf[k] = next + k; k++; }
    size_t u = ring_write_converted(&r, (uint8_t*)buf, (size_t)k * 4, IN_S32, 1, 4, 1); next += (int32_t)(u / 4); }
  return NULL; }
int main(void) { assert(ring_init(&r, 1000, 4) == 0); pthread_t t; pthread_create(&t, NULL, prod, NULL);
  int32_t expect = 0, out[37];
  while (expect < N) { uint32_t g = ring_read(&r, (uint8_t*)out, 37); for (uint32_t i = 0; i < g; i++) { assert(out[i] == expect); expect++; } }
  pthread_join(t, NULL); puts("SPSC stress passed (2M frames, in order)"); return 0; }
