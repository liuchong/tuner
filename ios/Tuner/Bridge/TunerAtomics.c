#include "TunerAtomics.h"

#include <stdatomic.h>
#include <stdlib.h>

typedef struct {
    _Atomic int64_t value;
} TunerAtomicInt;

void *tuner_atomic_int_create(int64_t initial_value) {
    TunerAtomicInt *atomic_value = malloc(sizeof(TunerAtomicInt));
    if (atomic_value == NULL) {
        return NULL;
    }
    atomic_init(&atomic_value->value, initial_value);
    return atomic_value;
}

void tuner_atomic_int_destroy(void *raw_value) {
    free(raw_value);
}

int64_t tuner_atomic_int_load_relaxed(void *raw_value) {
    TunerAtomicInt *atomic_value = raw_value;
    return atomic_load_explicit(&atomic_value->value, memory_order_relaxed);
}

int64_t tuner_atomic_int_load_acquire(void *raw_value) {
    TunerAtomicInt *atomic_value = raw_value;
    return atomic_load_explicit(&atomic_value->value, memory_order_acquire);
}

void tuner_atomic_int_store_release(void *raw_value, int64_t new_value) {
    TunerAtomicInt *atomic_value = raw_value;
    atomic_store_explicit(&atomic_value->value, new_value, memory_order_release);
}
