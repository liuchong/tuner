#ifndef TUNER_ATOMICS_H
#define TUNER_ATOMICS_H

#include <stdint.h>

void *tuner_atomic_int_create(int64_t initial_value);
void tuner_atomic_int_destroy(void *atomic_value);
int64_t tuner_atomic_int_load_relaxed(void *atomic_value);
int64_t tuner_atomic_int_load_acquire(void *atomic_value);
void tuner_atomic_int_store_release(void *atomic_value, int64_t new_value);

#endif
