/* SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * A TLS plaintext tap, for diagnosing registration.
 *
 * WhatsApp's registration talks to v.whatsapp.net over TLS through its own native stack (Tigon/mns ->
 * libssl.so, BoringSSL), so the request parameters and the server's verdict are encrypted on the wire:
 * the socket trace shows bytes moving but never *what*. This interposes BoringSSL's application-level
 * I/O -- SSL_read and SSL_write, which see plaintext, after decryption and before encryption -- logs a
 * readable slice of each, and passes the call straight through to the real function. It never changes a
 * buffer, a length or a return value, so it cannot alter the connection; it only observes.
 *
 * The slice is rendered printable (HTTP start-lines and JSON bodies are ASCII; HPACK/compressed bytes
 * show as dots), capped per call and over the run, so one registration attempt is captured without the
 * log turning into a firehose. The plaintext includes the phone number in the request: this is a
 * developer diagnostic, enabled to answer "what is the server actually saying", not a normal-build path.
 */
#define _DARWIN_C_SOURCE
#include <stddef.h>

#include "husk-tl-ld.h"

void tl_log_line(const char *fmt, ...);

/* int SSL_read(SSL*, void*, int) / int SSL_write(SSL*, const void*, int) -- BoringSSL's app I/O. */
typedef int (*ssl_io_fn)(void *ssl, void *buf, int num);
static ssl_io_fn g_real_read, g_real_write;

static void resolve_reals(void)
{
    if (g_real_read && g_real_write) return;
    /* libssl is necessarily loaded by the time its SSL_read/SSL_write is called; tl_ld_sym returns the
       real export, not this interpose, so there is no recursion. */
    tl_lib *ssl = tl_ld_find_lib("libssl.so");
    if (!ssl) return;
    if (!g_real_read)  g_real_read  = (ssl_io_fn)tl_ld_sym(ssl, "SSL_read");
    if (!g_real_write) g_real_write = (ssl_io_fn)tl_ld_sym(ssl, "SSL_write");
}

static void tap_log(const char *dir, const unsigned char *b, int n)
{
    static int budget = 500;         /* lines over the whole run: enough for one registration attempt */
    if (n <= 0 || budget <= 0) return;
    budget--;
    char line[385]; int j = 0;
    for (int i = 0; i < n && j < (int)sizeof(line) - 1; i++) {
        unsigned char c = b[i];
        line[j++] = (c >= 0x20 && c < 0x7f) ? (char)c : '.';
    }
    line[j] = 0;
    tl_log_line("tls-tap: %s %d: %s", dir, n, line);
}

static int tap_SSL_read(void *ssl, void *buf, int num)
{
    resolve_reals();
    int r = g_real_read ? g_real_read(ssl, buf, num) : 0;
    if (r > 0) tap_log("RX", (const unsigned char *)buf, r);
    return r;
}

static int tap_SSL_write(void *ssl, void *buf, int num)
{
    resolve_reals();
    if (num > 0) tap_log("TX", (const unsigned char *)buf, num);
    return g_real_write ? g_real_write(ssl, buf, num) : num;
}

/* Registered before the app's native libraries load, so their imports of these symbols bind to the taps. */
void tl_tls_tap_install(void)
{
    tl_ld_interpose("SSL_read", (void *)tap_SSL_read);
    tl_ld_interpose("SSL_write", (void *)tap_SSL_write);
}
