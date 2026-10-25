/*
 * ChairPlay: the stderr capture of stderr_capture.h.
 *
 * Thread safety: a spin lock (the compiler's __sync builtins) guards the buffer, held only for a memcpy, so the render
 * thread writing and the JNI thread draining cannot tear it. In practice both are the HUD's render thread.
 *
 * Part of ChairPlay, under the GNU GPL v3 (LICENSE in the repository root).
 */
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include "stderr_capture.h"

#define CAPTURE_SIZE (16 * 1024)
#define LINE_SIZE 2048

static char g_buffer[CAPTURE_SIZE];
static int g_length = 0;
static volatile int g_lock = 0;

static void lock(void) {
    while (__sync_lock_test_and_set(&g_lock, 1)) {}
}

static void unlock(void) {
    __sync_lock_release(&g_lock);
}

/* Appends text, dropping the oldest bytes when the buffer would pass CAPTURE_SIZE. */
static void append(const char *text, int length) {
    int dropped = 0;
    if (length >= CAPTURE_SIZE) {
        text += length - CAPTURE_SIZE;
        length = CAPTURE_SIZE;
        g_length = 0;
        dropped = 1;
    } else if (g_length + length > CAPTURE_SIZE) {
        int drop = g_length + length - CAPTURE_SIZE;
        memmove(g_buffer, g_buffer + drop, (size_t)(g_length - drop));
        g_length -= drop;
        dropped = 1;
    }
    memcpy(g_buffer + g_length, text, (size_t)length);
    g_length += length;
    if (dropped) {
        /* Start on a line boundary, so the text never begins inside a multibyte character. */
        char *newline = memchr(g_buffer, '\n', (size_t)g_length);
        int skip = newline ? (int)(newline - g_buffer) + 1 : 0;
        while (!newline && skip < g_length && ((unsigned char)g_buffer[skip] & 0xC0) == 0x80) skip++;
        memmove(g_buffer, g_buffer + skip, (size_t)(g_length - skip));
        g_length -= skip;
    }
}

int chairplay_fprintf(FILE *stream, const char *format, ...) {
    va_list args;
    int written;
    va_start(args, format);
    if (stream != stderr) {
        written = vfprintf(stream, format, args);
    } else {
        char line[LINE_SIZE];
        int kept;
        written = vsnprintf(line, sizeof line, format, args);
        if (written > 0) {
            kept = written < (int)sizeof line ? written : (int)sizeof line - 1;
            if (kept != written) {
                /* A cut must not end inside a UTF-8 sequence: drop an incomplete last character. */
                int start = kept;
                while (start > 0 && ((unsigned char)line[start - 1] & 0xC0) == 0x80) start--;
                if (start > 0) {
                    unsigned char lead = (unsigned char)line[start - 1];
                    int need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
                    if (kept - (start - 1) < need) kept = start - 1;
                }
            }
            lock();
            append(line, kept);
            unlock();
        }
    }
    va_end(args);
    return written;
}

int chairplay_stderr_take(char *out, int size) {
    int length;
    if (size <= 0) return 0;
    lock();
    length = g_length < size - 1 ? g_length : size - 1;
    memcpy(out, g_buffer + (g_length - length), (size_t)length);
    g_length = 0;
    unlock();
    out[length] = 0;
    return length;
}
