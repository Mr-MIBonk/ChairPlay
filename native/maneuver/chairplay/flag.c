/*
 * ChairPlay: the destination flag the renderer stands at the end of the route, drawn here frame by frame
 * (a chequered flag waving on a pole) instead of the MHI2Q project's flag_atlas.rgba, whose pictures
 * ChairPlay does not ship.
 *
 * The renderer anchors the sprite at the pole's foot, 22 % from the left and 88 % from the top of a frame
 * (render_sprite_flag_ex), and shows the frames one after another, side by side in one texture.
 *
 * Part of ChairPlay, under the GNU GPL v3 (LICENSE in the repository root).
 */
#include "flag.h"

#include <math.h>
#include <stdlib.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define POLE_X 0.22f
#define POLE_FOOT 0.88f
#define POLE_TOP 0.10f
#define POLE_HALF 0.018f
#define CLOTH_LEFT (POLE_X + POLE_HALF)
#define CLOTH_WIDTH 0.60f
#define CLOTH_TOP 0.13f
#define CLOTH_HEIGHT 0.34f
#define WAVE 0.035f
#define COLUMNS 5
#define ROWS 3
#define SAMPLES 4

typedef struct { float r, g, b, a; } rgba_t;

/* The colour at (u, v) of frame phase (0..1), straight alpha. */
static rgba_t sample(float u, float v, float phase) {
    rgba_t none = { 0, 0, 0, 0 };
    float dx = u - POLE_X, top = v - POLE_TOP;
    /* The pole, light grey, with a ball on top. */
    if (fabsf(dx) <= POLE_HALF && v >= POLE_TOP && v <= POLE_FOOT) {
        float shade = 0.80f + 0.18f * (1.0f - fabsf(dx) / POLE_HALF);
        rgba_t pole = { shade, shade, shade, 1 };
        return pole;
    }
    if (dx * dx + top * top <= (POLE_HALF * 1.6f) * (POLE_HALF * 1.6f)) {
        rgba_t ball = { 0.95f, 0.95f, 0.95f, 1 };
        return ball;
    }
    /* The cloth: a sine wave travelling away from the pole, growing with the distance from it. */
    if (u >= CLOTH_LEFT && u <= CLOTH_LEFT + CLOTH_WIDTH) {
        float s = (u - CLOTH_LEFT) / CLOTH_WIDTH;
        float angle = 2.0f * (float)M_PI * (s * 1.3f - phase);
        float lift = WAVE * sinf(angle) * (0.25f + 0.75f * s);
        float t = (v - CLOTH_TOP - lift) / CLOTH_HEIGHT;
        if (t >= 0.0f && t <= 1.0f) {
            /* Light falls on the slopes facing up. */
            float slope = cosf(angle);
            float light = 0.86f + 0.14f * slope;
            float edge = 0.035f;
            int column = (int)(s * COLUMNS), row = (int)(t * ROWS);
            float base = ((column + row) & 1) ? 0.16f : 0.97f;
            rgba_t cloth;
            if (s < edge * 0.5f || s > 1.0f - edge * 0.5f || t < edge || t > 1.0f - edge) base = 0.92f;
            cloth.r = cloth.g = cloth.b = base * light;
            cloth.a = 1;
            return cloth;
        }
    }
    return none;
}

unsigned char *cr_flag_atlas(int frame_size, int frame_count) {
    int atlas_w = frame_size * frame_count;
    unsigned char *pixels = (unsigned char *)calloc((size_t)atlas_w * frame_size, 4);
    int frame, x, y, i, j;
    if (!pixels) return NULL;
    for (frame = 0; frame < frame_count; frame++) {
        float phase = (float)frame / (float)frame_count;
        for (y = 0; y < frame_size; y++) {
            for (x = 0; x < frame_size; x++) {
                /* Supersampled for smooth edges; colour is averaged weighted by coverage. */
                float r = 0, g = 0, b = 0, a = 0;
                unsigned char *out = pixels + ((size_t)y * atlas_w + (size_t)frame * frame_size + x) * 4;
                for (j = 0; j < SAMPLES; j++) {
                    for (i = 0; i < SAMPLES; i++) {
                        float u = (x + (i + 0.5f) / SAMPLES) / frame_size;
                        float v = (y + (j + 0.5f) / SAMPLES) / frame_size;
                        rgba_t c = sample(u, v, phase);
                        r += c.r * c.a;
                        g += c.g * c.a;
                        b += c.b * c.a;
                        a += c.a;
                    }
                }
                if (a > 0) {
                    out[0] = (unsigned char)(r / a * 255.0f + 0.5f);
                    out[1] = (unsigned char)(g / a * 255.0f + 0.5f);
                    out[2] = (unsigned char)(b / a * 255.0f + 0.5f);
                    out[3] = (unsigned char)(a / (SAMPLES * SAMPLES) * 255.0f + 0.5f);
                }
            }
        }
    }
    return pixels;
}
