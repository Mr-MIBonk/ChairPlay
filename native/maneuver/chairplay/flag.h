/*
 * ChairPlay: the destination flag's frames (flag.c).
 *
 * Part of ChairPlay, under the GNU GPL v3 (LICENSE in the repository root).
 */
#ifndef CHAIRPLAY_FLAG_H
#define CHAIRPLAY_FLAG_H

/* frame_count square RGBA frames of frame_size pixels side by side, top row first, straight alpha;
 * malloc'ed, the caller frees. NULL when out of memory. */
unsigned char *cr_flag_atlas(int frame_size, int frame_count);

#endif
