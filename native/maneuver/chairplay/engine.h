/*
 * ChairPlay: the maneuver renderer driven frame by frame inside the caller's GL context (engine.c).
 * Every function runs on the thread whose OpenGL 3.3 core context is current.
 *
 * Part of ChairPlay, under the GNU GPL v3 (LICENSE in the repository root).
 */
#ifndef CHAIRPLAY_ENGINE_H
#define CHAIRPLAY_ENGINE_H

/* cr_engine_frame's result bits. */
#define CR_ENGINE_RENDERED  1  /* the texture was drawn again */
#define CR_ENGINE_ANIMATING 2  /* a transition runs: draw the next frame soon */
#define CR_ENGINE_LOOPING   4  /* only the destination flag waves: a slower frame rate will do */
#define CR_ENGINE_SHOWING   8  /* a maneuver is shown (not cleared) */

/* Creates the renderer drawing into a width x height RGBA texture and returns that texture, or 0 with a
 * reason in *error. The texture holds the renderer's whole 328 x 181 frame (protocol.h), bottom row first,
 * with premultiplied alpha. */
unsigned cr_engine_init(int width, int height, const char **error);
void cr_engine_resize(int width, int height);
/* Replaces the destination flag's pictures: frame_count frames of frame_w x frame_h RGBA, side by side in one
 * row, top row first; returns 0 before init or for a bad size. */
int cr_engine_flag_atlas(const unsigned char *rgba, int frame_w, int frame_h, int frame_count);
/* Queues one 48-byte command (protocol.h) for the next frame; returns 0 if it was not taken. */
int cr_engine_command(const unsigned char *packet, int length);
/* Applies the queued commands and draws if anything changed; now is a monotonic time in seconds. */
int cr_engine_frame(double now);
/* Reads the texture back into width x height x 4 bytes, bottom row first; for tests. */
int cr_engine_read(unsigned char *rgba, int width, int height);
void cr_engine_shutdown(void);

#endif
