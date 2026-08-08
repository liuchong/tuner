#ifndef TUNAR_ATOMICS_H
#define TUNAR_ATOMICS_H

#include <stdint.h>

void *tunar_atomic_int_create(int64_t initial_value);
void tunar_atomic_int_destroy(void *atomic_value);
int64_t tunar_atomic_int_load_relaxed(void *atomic_value);
int64_t tunar_atomic_int_load_acquire(void *atomic_value);
void tunar_atomic_int_store_release(void *atomic_value, int64_t new_value);

#endif
