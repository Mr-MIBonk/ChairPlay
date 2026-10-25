/*
 * ChairPlay: keeps the renderer's fprintf(stderr, ...) text inside the DLL, where the app drains it into its log
 * (jni.c drainLog). A GUI process has no console, so the text would be lost. The build force-includes this header into
 * every C source of chairplay_maneuver.dll (-include chairplay/stderr_capture.h), so the copied MHI2Q files need no edit.
 *
 * Part of ChairPlay, under the GNU GPL v3 (LICENSE in the repository root).
 */
#ifndef CHAIRPLAY_STDERR_CAPTURE_H
#define CHAIRPLAY_STDERR_CAPTURE_H

#include <stdio.h>

/* stream == stderr: appends the formatted text to a bounded buffer; any other stream: vfprintf. Returns what printf would. */
int chairplay_fprintf(FILE *stream, const char *format, ...);

/* Copies the collected text (NUL-terminated) into out, at most size - 1 bytes, and clears it. Returns the length. */
int chairplay_stderr_take(char *out, int size);

#define fprintf(stream, ...) chairplay_fprintf(stream, __VA_ARGS__)

#endif
