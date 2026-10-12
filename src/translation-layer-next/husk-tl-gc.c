/* SPDX-License-Identifier: GPL-2.0-or-later */
/* The Java heap's collector: see husk-tl-gc.h. */
#define _DARWIN_C_SOURCE
#include "husk-tl-gc.h"

#include <pthread.h>
#include <setjmp.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#include "husk-tl-jni.h"
#include "husk-tl-bionic.h"

#if defined(__APPLE__) && defined(__arm64__)
#define GC_CAN_COLLECT 1
#include <dlfcn.h>
#include <mach-o/dyld.h>
#include <mach-o/loader.h>
#include <mach/mach.h>
#include <malloc/malloc.h>
#include <TargetConditionals.h>
#if TARGET_OS_IPHONE
#include <os/proc.h>
#endif
#else
#define GC_CAN_COLLECT 0
#endif

void tl_ld_each_writable(void (*cb)(const void *start, size_t len, void *user), void *user);

/* ------------------------------------------------------------------ arenas */

#define CELL 80u                                    /* sizeof(jobj) rounded up to 16 */
#define ARENA_SIZE (4u << 20)
#define ARENA_META 65536u                           /* one state byte per cell, ahead of the cells */
#define ARENA_CELLS ((ARENA_SIZE - ARENA_META) / CELL)
#define MAX_ARENAS 16384                            /* 64 GB of headers: never reached */

enum { ST_ALLOC = 1, ST_MARK = 2, ST_YOUNG = 4 };

_Static_assert(sizeof(struct jobj) <= 72, "a jobj must fit a cell ahead of its free-list link");
/* A free cell's link to the next: in the cell's last 8 bytes, which no jobj field covers (a late write to a freed object's
   refs or fields cannot break the list). */
#define NEXT(o) (*(jobj **)((char *)(o) + 72))

typedef struct arena { uint8_t *base; uint32_t used; } arena;   /* used: cells handed out by bumping */

static arena g_arenas[MAX_ARENAS];                  /* sorted by base */
static int g_narenas;
static uintptr_t g_lo = UINTPTR_MAX, g_hi;
static pthread_mutex_t g_heap = PTHREAD_MUTEX_INITIALIZER;
static jobj *g_free;                                /* free cells, linked through NEXT */
static int g_bump = -1;                             /* the arena new cells are bumped from */
static size_t g_cells;                              /* cells in use */

static inline uint8_t *cells_of(const arena *a) { return a->base + ARENA_META; }

static int new_arena(void)
{
    if (g_narenas == MAX_ARENAS) return -1;
    void *m = mmap(NULL, ARENA_SIZE, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (m == MAP_FAILED) return -1;
    int i = g_narenas;
    while (i > 0 && g_arenas[i - 1].base > (uint8_t *)m) { g_arenas[i] = g_arenas[i - 1]; i--; }
    g_arenas[i].base = m; g_arenas[i].used = 0;
    g_narenas++;
    if (g_bump >= i) g_bump++;
    if ((uintptr_t)m < g_lo) g_lo = (uintptr_t)m;
    if ((uintptr_t)m + ARENA_SIZE > g_hi) g_hi = (uintptr_t)m + ARENA_SIZE;
    return i;
}

/* A cell's state byte, allocated or not (the cell is known to be in an arena). */
static uint8_t *state_byte(const jobj *o)
{
    uintptr_t p = (uintptr_t)o;
    int lo = 0, hi = g_narenas - 1;
    while (lo <= hi) {
        int mid = (lo + hi) >> 1; uintptr_t b = (uintptr_t)g_arenas[mid].base;
        if (p < b) hi = mid - 1; else if (p >= b + ARENA_SIZE) lo = mid + 1;
        else return g_arenas[mid].base + (p - b - ARENA_META) / CELL;
    }
    return NULL;
}

/* The arena a cell or word is in, and the cell's index; NULL when the word points into no handed-out cell. */
static inline uint8_t *state_of(uintptr_t p, jobj **cell)
{
    if (p < g_lo || p >= g_hi) return NULL;
    int lo = 0, hi = g_narenas - 1;
    while (lo <= hi) {
        int mid = (lo + hi) >> 1;
        uintptr_t b = (uintptr_t)g_arenas[mid].base;
        if (p < b) hi = mid - 1;
        else if (p >= b + ARENA_SIZE) lo = mid + 1;
        else {
            if (p < b + ARENA_META) return NULL;
            uint32_t idx = (uint32_t)((p - b - ARENA_META) / CELL);
            if (idx >= g_arenas[mid].used) return NULL;
            uint8_t *st = g_arenas[mid].base + idx;
            if (!(*st & ST_ALLOC)) return NULL;
            if (cell) *cell = (jobj *)(b + ARENA_META + (uintptr_t)idx * CELL);
            return st;
        }
    }
    return NULL;
}

/* ------------------------------------------------------------------ allocation accounting */

static atomic_bool g_enabled;
static _Atomic size_t g_since;                      /* bytes made since the last collection */
static size_t g_threshold = 32u << 20;
static size_t g_floor __attribute__((unused)) = 32u << 20;
static bool g_fixed __attribute__((unused));                              /* TL_GC_MB set the threshold */
static _Thread_local bool t_collecting;
static atomic_bool g_off;                           /* the collector found it cannot see the whole heap: never sweep again */

static void *payload_zone(void);

void *tl_gc_payload_calloc(size_t n, size_t size)
{
    atomic_fetch_add_explicit(&g_since, n * size, memory_order_relaxed);
#if GC_CAN_COLLECT
    malloc_zone_t *z = payload_zone();
    if (z) return malloc_zone_calloc(z, n ? n : 1, size ? size : 1);
#endif
    return calloc(n ? n : 1, size ? size : 1);
}

void *tl_gc_payload_malloc(size_t size)
{
    atomic_fetch_add_explicit(&g_since, size, memory_order_relaxed);
#if GC_CAN_COLLECT
    malloc_zone_t *z = payload_zone();
    if (z) return malloc_zone_malloc(z, size ? size : 1);
#endif
    return malloc(size ? size : 1);
}

char *tl_gc_payload_strdup(const char *s)
{
    if (!s) s = "";
    size_t n = strlen(s) + 1;
    char *d = tl_gc_payload_malloc(n);
    memcpy(d, s, n);
    return d;
}

static void maybe_collect(void);
static void maybe_collect_low(void);

jobj *tl_gc_alloc_cell(void)
{
    if (atomic_load_explicit(&g_enabled, memory_order_relaxed) && !t_collecting) {
        size_t since = atomic_load_explicit(&g_since, memory_order_relaxed);
        if (since >= g_threshold) maybe_collect();
#if TARGET_OS_IPHONE
        /* the phone running short counts too: every few thousand objects, a look at what is left before jetsam */
        else { static _Atomic unsigned tick; if ((atomic_fetch_add_explicit(&tick, 1, memory_order_relaxed) & 8191) == 0 && since >= (4u << 20) && os_proc_available_memory() < (450u << 20)) maybe_collect_low(); }
#endif
    }
    pthread_mutex_lock(&g_heap);
    jobj *o = g_free;
    uint8_t *st;
    if (o) {
        g_free = NEXT(o);
        st = state_byte(o);
    } else {
        if (g_bump < 0 || g_arenas[g_bump].used == ARENA_CELLS) {
            g_bump = -1;
            for (int k = 0; k < g_narenas; k++) if (g_arenas[k].used < ARENA_CELLS) { g_bump = k; break; }
            if (g_bump < 0) g_bump = new_arena();
            if (g_bump < 0) { pthread_mutex_unlock(&g_heap); return calloc(1, CELL); }   /* out of address space: an object the collector never sees */
        }
        arena *ar = &g_arenas[g_bump];
        uint32_t idx = ar->used;
        o = (jobj *)(cells_of(ar) + (size_t)idx * CELL);
        st = ar->base + idx;
        ar->used = idx + 1;
    }
    memset(o, 0, CELL);
    *st = ST_ALLOC | ST_YOUNG;
    g_cells++;
    pthread_mutex_unlock(&g_heap);
    atomic_fetch_add_explicit(&g_since, CELL, memory_order_relaxed);
    return o;
}

void tl_gc_free_cell(jobj *o)
{
    if (!o) return;
    pthread_mutex_lock(&g_heap);
    jobj *cell = NULL;
    uint8_t *st = state_of((uintptr_t)o, &cell);
    if (st && cell == o) {
        *st = 0;
        memset(o, 0, CELL);
        NEXT(o) = g_free; g_free = o;
        g_cells--;
        pthread_mutex_unlock(&g_heap);
        return;
    }
    pthread_mutex_unlock(&g_heap);
    free(o);                                        /* made by calloc when no arena could be mapped */
}

bool tl_gc_enabled(void) { return atomic_load(&g_enabled) && !atomic_load(&g_off); }

#if !GC_CAN_COLLECT

static void *payload_zone(void) { return NULL; }
void tl_gc_enable(void) { tl_log_line("gc: no collector on this platform; the Java heap only grows"); }
void tl_gc_collect(const char *why) { (void)why; }
static void maybe_collect(void) {}
static void maybe_collect_low(void) {}

#else

/* ------------------------------------------------------------------ the payload zone */

static malloc_zone_t *g_payload;
static pthread_once_t g_payload_once = PTHREAD_ONCE_INIT;
static void payload_init(void)
{
    malloc_zone_t *z = malloc_create_zone(0, 0);
    /* a zone this process cannot tell from the default one would hide every root in it: then payloads are ordinary blocks */
    if (z && z != malloc_default_zone()) { malloc_set_zone_name(z, "husk-java-objects"); g_payload = z; }
}
static void *payload_zone(void) { pthread_once(&g_payload_once, payload_init); return g_payload; }

/* ------------------------------------------------------------------ marking */

static uintptr_t *g_stack;                          /* mark stack: mapped once, never from malloc (taken while the heap is locked) */
static size_t g_stack_cap, g_sp;
static bool g_overflow;

static inline void push(jobj *o) { if (g_sp < g_stack_cap) g_stack[g_sp++] = (uintptr_t)o; else g_overflow = true; }

static inline void mark_word(uintptr_t w)
{
    if (w < g_lo || w >= g_hi) return;
    jobj *o;
    uint8_t *st = state_of(w, &o);
    if (!st || (*st & ST_MARK)) return;
    *st |= ST_MARK;
    push(o);
}

static size_t g_scanned;

static void scan_range(const void *lo, const void *hi)
{
    uintptr_t p = ((uintptr_t)lo + 7) & ~(uintptr_t)7, e = (uintptr_t)hi & ~(uintptr_t)7;
    if (e <= p) return;
    g_scanned += e - p;
    for (; p < e; p += 8) mark_word(*(const uintptr_t *)p);
}

static void trace(jobj *o)
{
    switch (o->kind) {
    case TL_K_OBJECT: case TL_K_CLASS: {
        jvalue *f = o->fields; uint32_t n = o->nfields;
        if (f) for (uint32_t i = 0; i < n; i++) mark_word((uintptr_t)f[i].l);
        break;
    }
    case TL_K_OBJ_ARRAY: {
        jobj **v = o->oarr.v; uint32_t n = o->oarr.len;
        if (v) for (uint32_t i = 0; i < n; i++) mark_word((uintptr_t)v[i]);
        break;
    }
    default: break;
    }
}

static void drain(void)
{
    for (;;) {
        while (g_sp) trace((jobj *)g_stack[--g_sp]);
        if (!g_overflow) return;
        /* the mark stack ran out: every marked object is traced again, which pushes the ones that were dropped */
        g_overflow = false;
        for (int a = 0; a < g_narenas; a++) {
            arena *ar = &g_arenas[a];
            for (uint32_t i = 0; i < ar->used; i++) if ((ar->base[i] & (ST_ALLOC | ST_MARK)) == (ST_ALLOC | ST_MARK)) {
                trace((jobj *)(cells_of(ar) + (size_t)i * CELL));
                while (g_sp) trace((jobj *)g_stack[--g_sp]);
            }
        }
    }
}

/* ------------------------------------------------------------------ the world */

#define MAX_THREADS 2048
static thread_act_t g_susp[MAX_THREADS];
static int g_nsusp;

static bool suspended(thread_act_t t) { for (int i = 0; i < g_nsusp; i++) if (g_susp[i] == t) return true; return false; }

static void stop_world(void)
{
    thread_act_t self = mach_thread_self();
    g_nsusp = 0;
    /* threads the ones still running start meanwhile: asked again until a pass finds none new */
    for (int round = 0; round < 16; round++) {
        thread_act_array_t th; mach_msg_type_number_t n;
        if (task_threads(mach_task_self(), &th, &n) != KERN_SUCCESS) break;
        bool fresh = false;
        for (mach_msg_type_number_t i = 0; i < n; i++) {
            if (th[i] != self && !suspended(th[i]) && g_nsusp < MAX_THREADS && thread_suspend(th[i]) == KERN_SUCCESS) { g_susp[g_nsusp++] = th[i]; fresh = true; continue; }
            mach_port_deallocate(mach_task_self(), th[i]);
        }
        vm_deallocate(mach_task_self(), (vm_address_t)th, n * sizeof(thread_act_t));
        if (!fresh) break;
    }
    mach_port_deallocate(mach_task_self(), self);
}

static void start_world(void)
{
    for (int i = 0; i < g_nsusp; i++) { thread_resume(g_susp[i]); mach_port_deallocate(mach_task_self(), g_susp[i]); }
    g_nsusp = 0;
}

/* The mapping an address is in, when it is mapped and readable. */
static bool region_of(uintptr_t addr, uintptr_t *start, uintptr_t *end)
{
    vm_address_t a = (vm_address_t)addr; vm_size_t sz = 0;
    vm_region_basic_info_data_64_t info; mach_msg_type_number_t cnt = VM_REGION_BASIC_INFO_COUNT_64; mach_port_t obj = MACH_PORT_NULL;
    if (vm_region_64(mach_task_self(), &a, &sz, VM_REGION_BASIC_INFO_64, (vm_region_info_t)&info, &cnt, &obj) != KERN_SUCCESS) return false;
    if (obj != MACH_PORT_NULL) mach_port_deallocate(mach_task_self(), obj);
    if (a > addr || !(info.protection & VM_PROT_READ)) return false;
    *start = a; *end = a + sz;
    return true;
}

static size_t g_stack_bytes;

static void scan_stack_from(uintptr_t sp)
{
    uintptr_t s, e;
    if (!sp || !region_of(sp, &s, &e)) return;
    if (e - sp > (64u << 20)) e = sp + (64u << 20);
    g_stack_bytes += e - sp;
    scan_range((const void *)sp, (const void *)e);
}

static void scan_threads(void)
{
    for (int i = 0; i < g_nsusp; i++) {
        arm_thread_state64_t ts; mach_msg_type_number_t c = ARM_THREAD_STATE64_COUNT;
        if (thread_get_state(g_susp[i], ARM_THREAD_STATE64, (thread_state_t)&ts, &c) != KERN_SUCCESS) continue;
        scan_range(&ts.__x[0], &ts.__x[29]);
        arm_neon_state64_t ns; c = ARM_NEON_STATE64_COUNT;
        if (thread_get_state(g_susp[i], ARM_NEON_STATE64, (thread_state_t)&ns, &c) == KERN_SUCCESS) scan_range(&ns.__v[0], &ns.__v[32]);
        scan_stack_from((uintptr_t)arm_thread_state64_get_sp(ts));
    }
}

static void __attribute__((noinline)) scan_own_stack(void)
{
    jmp_buf jb;                                     /* the callee-saved registers, spilled where the scan sees them */
    setjmp(jb);
    scan_range(&jb, (const char *)&jb + sizeof(jb));
    scan_stack_from((uintptr_t)__builtin_frame_address(0));
}

/* The app's own writable data (its C globals): found once, before any collection -- dyld's lock may be held by a stopped thread. */
static struct { const uint8_t *lo, *hi; } g_data[16];
static int g_ndata;

static void find_image_data(void)
{
    Dl_info di;
    if (!dladdr((const void *)&g_arenas, &di) || !di.dli_fbase) return;
    const struct mach_header_64 *mh = di.dli_fbase;
    intptr_t slide = 0;
    for (uint32_t i = 0; i < _dyld_image_count(); i++) if ((const void *)_dyld_get_image_header(i) == (const void *)mh) { slide = _dyld_get_image_vmaddr_slide(i); break; }
    const uint8_t *lc = (const uint8_t *)(mh + 1);
    for (uint32_t i = 0; i < mh->ncmds && g_ndata < 15; i++) {
        const struct load_command *l = (const void *)lc;
        if (l->cmd == LC_SEGMENT_64) {
            const struct segment_command_64 *sg = (const void *)lc;
            if (!strncmp(sg->segname, "__DATA", 6) && sg->vmsize) {
                const uint8_t *s = (const uint8_t *)(uintptr_t)(sg->vmaddr + slide), *e = s + sg->vmsize;
                /* the collector's own tables are not roots */
                const uint8_t *skip_lo = (const uint8_t *)g_arenas, *skip_hi = (const uint8_t *)(g_arenas + MAX_ARENAS);
                if (skip_lo >= s && skip_hi <= e) { g_data[g_ndata].lo = s; g_data[g_ndata++].hi = skip_lo; g_data[g_ndata].lo = skip_hi; g_data[g_ndata++].hi = e; }
                else { g_data[g_ndata].lo = s; g_data[g_ndata++].hi = e; }
            }
        }
        lc += l->cmdsize;
    }
}

static void scan_image_data(void) { for (int i = 0; i < g_ndata; i++) scan_range(g_data[i].lo, g_data[i].hi); }

static void scan_guest_cb(const void *start, size_t len, void *user) { (void)user; scan_range(start, (const char *)start + len); }

/* Every block in use in every malloc zone but the payloads'. */
#define NPROBES 3
static uintptr_t g_probe[NPROBES]; static bool g_probe_seen[NPROBES];
static size_t g_heap_bytes, g_heap_blocks, g_big_skipped;
#define MAX_BLOCK (32u << 20)

/* What each zone's walk saw, for the log (kept in place: nothing may be allocated while the heap is locked). */
#define MAX_ZONES 32
static struct { kern_return_t kr; size_t blocks, bytes; } g_zstat[MAX_ZONES];
static unsigned g_zcur;

/* This process reads its own memory where it is. */
static kern_return_t self_reader(task_t task, vm_address_t addr, vm_size_t size, void **out) { (void)task; (void)size; *out = (void *)addr; return KERN_SUCCESS; }

static void record(task_t task, void *ctx, unsigned type, vm_range_t *r, unsigned n)
{
    (void)task; (void)ctx; (void)type;
    if (g_zcur < MAX_ZONES) g_zstat[g_zcur].blocks += n;
    for (unsigned i = 0; i < n; i++) {
        uintptr_t a = r[i].address; size_t sz = r[i].size;
        if (g_zcur < MAX_ZONES) g_zstat[g_zcur].bytes += sz;
        for (int k = 0; k < NPROBES; k++) if (g_probe[k] >= a && g_probe[k] < a + sz) g_probe_seen[k] = true;
        if (sz > MAX_BLOCK) { g_big_skipped++; continue; }
        g_heap_blocks++; g_heap_bytes += sz;
        scan_range((const void *)a, (const void *)(a + sz));
    }
}

static vm_address_t *g_zones; static unsigned g_nzones;

static void lock_zones(void)
{
    g_zones = NULL; g_nzones = 0;
    if (malloc_get_all_zones(mach_task_self(), self_reader, &g_zones, &g_nzones) != KERN_SUCCESS) { g_zones = NULL; g_nzones = 0; return; }
    for (unsigned i = 0; i < g_nzones; i++) { malloc_zone_t *z = (malloc_zone_t *)g_zones[i]; if (z && z->introspect && z->introspect->force_lock) z->introspect->force_lock(z); }
}

static void unlock_zones(void)
{
    for (unsigned i = g_nzones; i-- > 0;) { malloc_zone_t *z = (malloc_zone_t *)g_zones[i]; if (z && z->introspect && z->introspect->force_unlock) z->introspect->force_unlock(z); }
}

static bool scan_malloc(void)
{
    for (unsigned i = 0; i < g_nzones; i++) {
        malloc_zone_t *z = (malloc_zone_t *)g_zones[i];
        g_zcur = i;
        if (i < MAX_ZONES) { g_zstat[i].kr = -1; g_zstat[i].blocks = g_zstat[i].bytes = 0; }
        if (!z || z == g_payload || !z->introspect || !z->introspect->enumerator) continue;
        kern_return_t kr = z->introspect->enumerator(mach_task_self(), NULL, MALLOC_PTR_IN_USE_RANGE_TYPE, (vm_address_t)z, self_reader, record);
        if (i < MAX_ZONES) g_zstat[i].kr = kr;
    }
    for (int k = 0; k < NPROBES; k++) if (!g_probe_seen[k]) return false;
    return true;
}

/* When a zone cannot be walked (an allocator that does not implement it, as a jemalloc zone some libraries install): every
   writable page the process has in memory is scanned instead -- all of malloc's whatever its kind, and every anonymous mapping.
   Left out: the payload zone's regions (payloads are reached through their objects), the mark stack, graphics memory, and
   mappings too big to be a heap (the JIT region). Pages not in memory hold nothing and are not touched. */
#define MAX_EXCL 8192
static struct { uintptr_t lo, hi; } g_excl[MAX_EXCL];
static int g_nexcl;
static size_t g_region_bytes;
static bool g_region_mode;

static void payload_regions(task_t task, void *ctx, unsigned type, vm_range_t *r, unsigned n)
{
    (void)task; (void)ctx; (void)type;
    for (unsigned i = 0; i < n && g_nexcl < MAX_EXCL; i++) { g_excl[g_nexcl].lo = r[i].address; g_excl[g_nexcl].hi = r[i].address + r[i].size; g_nexcl++; }
}

static void probe_check(uintptr_t a, uintptr_t e) { for (int k = 0; k < NPROBES; k++) if (g_probe[k] >= a && g_probe[k] < e) g_probe_seen[k] = true; }

/* [a, e) less the exclusions, page by page where the page is in memory */
static void scan_resident(uintptr_t a, uintptr_t e)
{
    static char vec[65536];
    const uintptr_t pg = (uintptr_t)getpagesize();
    while (a < e) {
        uintptr_t chunk_end = e;
        if ((chunk_end - a) / pg > sizeof(vec)) chunk_end = a + sizeof(vec) * pg;
        if (mincore((void *)a, chunk_end - a, (void *)vec) != 0) { a = chunk_end; continue; }
        for (uintptr_t p = a; p < chunk_end; p += pg) {
            if (!(vec[(p - a) / pg] & 1)) continue;
            uintptr_t pe = p + pg;
            bool skip = false;
            for (int x = 0; x < g_nexcl && !skip; x++) if (p < g_excl[x].hi && pe > g_excl[x].lo) {
                /* partly excluded: the parts outside */
                if (p < g_excl[x].lo) { probe_check(p, g_excl[x].lo); scan_range((const void *)p, (const void *)g_excl[x].lo); g_region_bytes += g_excl[x].lo - p; }
                if (pe > g_excl[x].hi) { probe_check(g_excl[x].hi, pe); scan_range((const void *)g_excl[x].hi, (const void *)pe); g_region_bytes += pe - g_excl[x].hi; }
                skip = true;
            }
            if (skip) continue;
            probe_check(p, pe);
            g_region_bytes += pg;
            scan_range((const void *)p, (const void *)pe);
        }
        a = chunk_end;
    }
}

static bool skip_tag(unsigned tag)
{
    switch (tag) {
    case 21:  /* IOKit */
    case 51:  /* CoreAnimation layer backing */
    case 52:  /* CGImage */
    case 78:  /* GPU (IOAccelerator) */
    case 89:  /* IOSurface */
        return true;
    default: return false;
    }
}

static bool scan_all_writable(void)
{
    g_region_mode = true;
    g_nexcl = 0;
    if (g_payload && g_payload->introspect && g_payload->introspect->enumerator)
        g_payload->introspect->enumerator(mach_task_self(), NULL, MALLOC_PTR_REGION_RANGE_TYPE, (vm_address_t)g_payload, self_reader, payload_regions);
    if (g_nexcl < MAX_EXCL) { g_excl[g_nexcl].lo = (uintptr_t)g_stack; g_excl[g_nexcl].hi = (uintptr_t)g_stack + g_stack_cap * sizeof(uintptr_t); g_nexcl++; }
    for (int k = 0; k < NPROBES; k++) g_probe_seen[k] = false;
    vm_address_t addr = 0; natural_t depth = 0;
    for (int guard = 0; guard < 200000; guard++) {
        vm_size_t size = 0;
        vm_region_submap_info_data_64_t info; mach_msg_type_number_t cnt = VM_REGION_SUBMAP_INFO_COUNT_64;
        if (vm_region_recurse_64(mach_task_self(), &addr, &size, &depth, (vm_region_recurse_info_t)&info, &cnt) != KERN_SUCCESS) break;
        if (info.is_submap) { depth++; continue; }
        if ((info.protection & (VM_PROT_READ | VM_PROT_WRITE)) == (VM_PROT_READ | VM_PROT_WRITE) && size <= (256u << 20) && !skip_tag(info.user_tag))
            scan_resident(addr, addr + size);
        addr += size;
    }
    for (int k = 0; k < NPROBES; k++) if (!g_probe_seen[k]) return false;
    return true;
}

/* ------------------------------------------------------------------ sweeping */

static size_t g_live_bytes;

static size_t payload_bytes(const jobj *o)
{
    switch (o->kind) {
    case TL_K_STRING: return (o->str.utf8 ? strlen(o->str.utf8) + 1 : 0) + (o->str.u16 ? (size_t)o->str.len16 * 2 : 0);
    case TL_K_PRIM_ARRAY: return (size_t)o->arr.len * o->arr.esz;
    case TL_K_OBJ_ARRAY: return (size_t)o->oarr.len * sizeof(jobj *);
    default: return (size_t)o->nfields * sizeof(jvalue);
    }
}

typedef struct mon_hdr { pthread_mutex_t mu; pthread_cond_t cv; } mon_hdr;   /* the head of husk-tl-dvm.c's monitor */

static void release(jobj *o)
{
    switch (o->kind) {
    case TL_K_STRING: free(o->str.utf8); free(o->str.u16); break;
    case TL_K_PRIM_ARRAY: free(o->arr.data); break;
    case TL_K_OBJ_ARRAY: free(o->oarr.v); break;
    default: break;
    }
    free(o->fields);
    if (o->monitor) { mon_hdr *m = o->monitor; pthread_mutex_destroy(&m->mu); pthread_cond_destroy(&m->cv); free(m); }
    /* o->native is a structure some implementation shares (reflection's field and method records): not the object's to free */
}

static size_t sweep(size_t *freed_bytes)
{
    size_t freed = 0, fb = 0, live = 0;
    for (int a = 0; a < g_narenas; a++) {
        arena *ar = &g_arenas[a];
        uint8_t *cells = cells_of(ar);
        for (uint32_t i = 0; i < ar->used; i++) {
            uint8_t s = ar->base[i];
            if (!(s & ST_ALLOC)) continue;
            jobj *o = (jobj *)(cells + (size_t)i * CELL);
            if ((s & ST_MARK) || o->kind == TL_K_CLASS) { ar->base[i] = ST_ALLOC; live += CELL + payload_bytes(o); continue; }
            fb += CELL + payload_bytes(o);
            release(o);
            memset(o, 0, CELL);
            ar->base[i] = 0;
            NEXT(o) = g_free; g_free = o;
            freed++;
        }
    }
    g_cells -= freed;
    g_live_bytes = live;
    *freed_bytes = fb;
    return freed;
}

/* ------------------------------------------------------------------ a collection */

static unsigned g_count;

static double now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1e3 + t.tv_nsec / 1e6; }

static size_t mem_left(void)
{
#if TARGET_OS_IPHONE
    return os_proc_available_memory();
#else
    return 0;
#endif
}

static void collect(const char *why)
{
    if (t_collecting) return;
    t_collecting = true;
    if (!g_stack) {
        g_stack_cap = (64u << 20) / sizeof(uintptr_t);
        void *m = mmap(NULL, g_stack_cap * sizeof(uintptr_t), PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
        if (m == MAP_FAILED) { g_stack_cap = 0; t_collecting = false; return; }
        g_stack = m;
    }
    payload_zone();
    /* blocks of each size class the heap walk must find, or it is not seeing the whole heap */
    static const size_t probe_size[NPROBES] = { 48, 3000, 300000 };
    void *probe[NPROBES];
    for (int k = 0; k < NPROBES; k++) { probe[k] = malloc(probe_size[k]); if (probe[k]) memset(probe[k], 0x5a, 64); }   /* written: a page never touched is not in memory, and a page scan rightly skips it */
    double t0 = now_ms();
    pthread_mutex_lock(&g_heap);
    size_t before = g_cells;
    g_scanned = 0; g_stack_bytes = 0; g_heap_bytes = 0; g_heap_blocks = 0; g_big_skipped = 0;
    for (int k = 0; k < NPROBES; k++) { g_probe[k] = (uintptr_t)probe[k]; g_probe_seen[k] = false; }
    g_sp = 0; g_overflow = false;

    lock_zones();
    stop_world();
    double t1 = now_ms();

    /* roots: objects JNI holds, objects too young to judge, every class's mirror */
    for (int a = 0; a < g_narenas; a++) {
        arena *ar = &g_arenas[a];
        for (uint32_t i = 0; i < ar->used; i++) {
            uint8_t s = ar->base[i];
            if (!(s & ST_ALLOC)) continue;
            jobj *o = (jobj *)(cells_of(ar) + (size_t)i * CELL);
            if ((s & ST_YOUNG) || o->grefs || o->kind == TL_K_CLASS) { ar->base[i] = (uint8_t)((s & ~ST_YOUNG) | ST_MARK); push(o); }
            else ar->base[i] = s & ~ST_MARK;
        }
    }
    scan_threads();
    scan_own_stack();
    scan_image_data();
    tl_ld_each_writable(scan_guest_cb, NULL);
    g_region_mode = false; g_region_bytes = 0;
    bool whole = scan_malloc() || scan_all_writable();
    drain();
    double t2 = now_ms();

    int nthreads = g_nsusp;
    start_world();
    unlock_zones();

    size_t freed = 0, freed_bytes = 0;
    if (whole) freed = sweep(&freed_bytes);
    else {
        /* nothing is freed when the walk missed a block it was shown: an object only C holds there would be freed in use */
        for (int a = 0; a < g_narenas; a++) for (uint32_t i = 0; i < g_arenas[a].used; i++) g_arenas[a].base[i] &= (uint8_t)~ST_MARK;
        atomic_store(&g_off, true);
    }
    atomic_store(&g_since, 0);
    if (!g_fixed) {
        size_t t = g_live_bytes / 2;
        if (t < g_floor) t = g_floor;
        if (t > (192u << 20)) t = 192u << 20;
        size_t left = mem_left();
        if (left && left < (700u << 20)) t = g_floor / 2;
        g_threshold = t;
    }
    size_t live = g_cells;
    pthread_mutex_unlock(&g_heap);
    for (int k = 0; k < NPROBES; k++) free(probe[k]);
    double t3 = now_ms();
    g_count++;
    t_collecting = false;
    if (!whole || g_count <= 2) {
        if (g_region_mode) tl_log_line("gc:   a zone could not be walked: every writable page in memory was scanned instead (%.1f MiB, %d ranges left out)%s",
                                       g_region_bytes / 1048576.0, g_nexcl, whole ? "" : " -- and still did not find every probe");
        if (!whole) tl_log_line("gc: the malloc heap walk did not see a block it was shown (48 B %s, 3000 B %s, 300000 B %s): the collector stays off, nothing was freed",
                                g_probe_seen[0] ? "seen" : "MISSED", g_probe_seen[1] ? "seen" : "MISSED", g_probe_seen[2] ? "seen" : "MISSED");
        for (unsigned i = 0; i < g_nzones && i < MAX_ZONES; i++) {
            malloc_zone_t *z = (malloc_zone_t *)g_zones[i];
            tl_log_line("gc:   zone %u %s%s: walk %d, %zu blocks, %.1f MiB", i, z && z->zone_name ? z->zone_name : "?", z == g_payload ? " (payloads, not walked)" : "",
                        g_zstat[i].kr, g_zstat[i].blocks, g_zstat[i].bytes / 1048576.0);
        }
        if (!whole) return;
    }
    tl_log_line("gc: #%u (%s) freed %zu of %zu objects (%.1f MiB); %zu live (%.1f MiB); paused %.1f ms (stop %.1f, mark %.1f), sweep %.1f ms; "
                "scanned %.1f MiB (%zu threads' stacks %.1f MiB, %zu heap blocks %.1f MiB, %zu big skipped); next after %.0f MiB; %zu MiB left%s",
                g_count, why, freed, before, freed_bytes / 1048576.0, live, g_live_bytes / 1048576.0, t2 - t0, t1 - t0, t2 - t1, t3 - t2,
                g_scanned / 1048576.0, (size_t)nthreads, g_stack_bytes / 1048576.0, g_heap_blocks, g_heap_bytes / 1048576.0, g_big_skipped,
                g_threshold / 1048576.0, mem_left() >> 20, g_region_mode ? "; roots by page scan" : "");
}

/* One thread collects; the others go on (the next allocation past the threshold asks again). */
static atomic_flag g_busy = ATOMIC_FLAG_INIT;

static void maybe_collect(void)
{
    if (atomic_load(&g_off)) return;
    if (atomic_flag_test_and_set(&g_busy)) return;
    if (atomic_load_explicit(&g_since, memory_order_relaxed) >= g_threshold) collect("threshold");
    atomic_flag_clear(&g_busy);
}

static void __attribute__((unused)) maybe_collect_low(void)
{
    if (atomic_load(&g_off)) return;
    if (atomic_flag_test_and_set(&g_busy)) return;
    collect("memory low");
    atomic_flag_clear(&g_busy);
}

void tl_gc_collect(const char *why)
{
    if (!tl_gc_enabled()) return;
    if (atomic_flag_test_and_set(&g_busy)) return;
    collect(why ? why : "asked");
    atomic_flag_clear(&g_busy);
}

void tl_gc_enable(void)
{
    const char *e = getenv("TL_GC");
    if (e && !strcmp(e, "0")) { tl_log_line("gc: off (TL_GC=0)"); return; }
    const char *mb = getenv("TL_GC_MB");
    if (mb && atoi(mb) > 0) { g_threshold = g_floor = (size_t)atoi(mb) << 20; g_fixed = true; }
    payload_zone();
    find_image_data();
    atomic_store(&g_enabled, true);
    tl_log_line("gc: on; a collection every %zu MiB made (payload zone %s)", g_threshold >> 20, g_payload ? "separate" : "the default one");
}

#endif
