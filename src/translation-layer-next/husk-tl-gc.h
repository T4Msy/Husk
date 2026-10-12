/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * The Java heap's collector.
 *
 * Every jobj header lives in a cell of an arena this file maps, so any machine word can be told to be (or point into) an object
 * in a few instructions. Collection is mark and sweep, stop-the-world and conservative: the roots are every thread's registers
 * and stack, the app's and the guest libraries' writable data, and every block of the native malloc heap -- whatever C, the
 * framework's natives or the app's own libraries keep a jobject in. Objects reach other objects only through their payloads
 * (instance slots, object-array elements), which come from a malloc zone of their own that is not scanned as roots, so garbage
 * does not keep garbage alive. JNI global references pin; an object also survives the first collection after it was made.
 */
#ifndef HUSK_TL_GC_H
#define HUSK_TL_GC_H

#include <stdbool.h>
#include <stddef.h>

typedef struct jobj jobj;

jobj *tl_gc_alloc_cell(void);                       /* a zeroed object header; may collect first */
void tl_gc_free_cell(jobj *o);                      /* reference-counted engines (no Java runtime) free their own */

/* Object payloads: instance slots, array storage, string characters. */
void *tl_gc_payload_calloc(size_t n, size_t size);
void *tl_gc_payload_malloc(size_t size);
char *tl_gc_payload_strdup(const char *s);

void tl_gc_enable(void);                            /* the Java app runtime: unreachable objects are freed from now on */
bool tl_gc_enabled(void);
void tl_gc_collect(const char *why);                /* now, from any thread that holds no runtime lock */

#endif
