/*
 * ChairPlay: the maneuver renderer's engine, driven one frame at a time from the HUD instead of its own
 * window loop. It renders into a texture of ours, inside the caller's GL context.
 *
 * The state machine (IDLE, PUSHING, SLIDING_IN), the command handling, the fade from CLEAR, the arrow
 * progress and the lane panel are the MHI2Q project's maneuver_render/main.c, taken over unchanged; what
 * is gone is what belongs to its process: the TCP server, the QNX window, the watchdog, the screenshot,
 * the heartbeat and the frame pacing. Commands are the same 48-byte packets (protocol.h), handed over by
 * cr_engine_command instead of read from a socket.
 *
 * Copyright (c) 2026 LuKa (@LuKa_dev): the engine from maneuver_render/main.c.
 * ChairPlay's changes are under the same licence, the GNU GPL v3 or later.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../gl_compat.h"
#include "../render.h"
#include "../arrow_progress.h"
#include "../protocol.h"
#include "../maneuver_command.h"
#include "../maneuver.h"
#include "../scene/scene.h"
#include "../lane_guidance.h"
#include "../lane_panel.h"
#include "engine.h"
#include "flag.h"

#define TARGET_FPS 30
#define FLAG_FRAME 128
#define FLAG_FRAMES 14

/* ================================================================
 * Engine state machine (maneuver_render/main.c)
 *
 * IDLE:       showing current maneuver, slide=1.0
 * PUSHING:    slide 1->2, current pushes out + next slides in
 * SLIDING_IN: commit next->current, slide 0->1
 * ================================================================ */

typedef enum {
    ENGINE_IDLE,
    ENGINE_PUSHING,
    ENGINE_SLIDING_IN
} engine_phase_t;

typedef struct {
    maneuver_state_t current;
    maneuver_state_t next;
    maneuver_state_t pending;
    cr_scene_t *current_scene, *next_scene;
    int              has_current;
    int              has_next;
    int              has_pending;   /* queued during PUSHING */
    engine_phase_t   phase;
    int              dirty;
} cr_engine_t;

static cr_engine_t g_engine;
static cr_lane_guidance_t g_lane_guidance;
static cr_lane_decoder_t g_lane_decoder;
static cr_lane_panel_t *g_lane_panel;

static cr_scene_t *engine_scene(const maneuver_state_t *m) {
    if(m==&g_engine.current)return g_engine.current_scene;
    if(m==&g_engine.next)return g_engine.next_scene;
    return NULL;
}
static int scene_handles(void *ctx,const maneuver_state_t *m) {
    cr_scene_t *s=engine_scene(m);(void)ctx;
    return s && cr_scene_route(s) && !cr_scene_is_native(s);
}
static void scene_route(void *ctx,const maneuver_state_t *m,route_path_t *out) {
    const route_path_t *p=cr_scene_route(engine_scene(m));(void)ctx;
    if(p)*out=*p;else memset(out,0,sizeof(*out));
}
static float scene_elevation(void *ctx,const maneuver_state_t *m) {
    const cr_scene_info_t *info=cr_scene_info(engine_scene(m));(void)ctx;
    return info?info->route_elevation:0;
}
static void scene_paint(void *ctx,const maneuver_state_t *m,float x,float y,float c,float s) {
    (void)ctx;cr_scene_paint(engine_scene(m),x,y,c,s);
}
static void prepare_engine_scene(cr_scene_t *scene,const maneuver_state_t *m) {
    if(!scene)return;
    cr_scene_input_t in;cr_scene_view_t view;
    memset(&in,0,sizeof(in));in.maneuver=*m;
    view.compact=1;render_get_layout_matrix(view.projection);
    if(!cr_scene_prepare(scene,&in,&view))
        fprintf(stderr,"scene: invalid input; ordinary maneuver fallback\n");
}
static void prepare_engine_scenes(void) {
    prepare_engine_scene(g_engine.current_scene,&g_engine.current);
    if(g_engine.has_next)prepare_engine_scene(g_engine.next_scene,&g_engine.next);
}

static float g_fade_alpha = 1.0f;
static int   g_fade_active = 0;
#define FADE_SPEED 0.125f  /* per-frame step (~0.27s / 8 frames at 30fps) */
static int   g_cleared = 1;

static cr_arrow_progress_t g_arrow;
static int g_pending_progress_level=16,g_pending_progress_state=CR_PROGRESS_OFF;
static int   g_persp_deferred = 0;
static int   g_persp_deferred_value = 1;

static void engine_apply_maneuver(const maneuver_state_t *state,double now) {
    if (state->icon == ICON_NONE || g_engine.current.icon == ICON_NONE) {
        g_engine.current = *state;
        g_engine.has_current = 1;
        g_engine.has_next = g_engine.has_pending = 0;
        g_engine.phase = ENGINE_IDLE;
        cr_progress_reset(&g_arrow);
        maneuver_set_slide(1.0f);
        render_invalidate_masks();
        g_engine.dirty = 1;
        return;
    }
    if (g_engine.phase == ENGINE_PUSHING || g_engine.phase == ENGINE_SLIDING_IN) {
        g_engine.pending = *state;
        g_engine.has_pending = 1;
        g_pending_progress_level=16;g_pending_progress_state=CR_PROGRESS_OFF;
        return;
    }
    g_engine.next = *state;
    g_engine.has_next = 1;
    cr_progress_begin_handoff(&g_arrow,now);
    maneuver_start_push();
    g_engine.phase = ENGINE_PUSHING;
    render_invalidate_masks();
    g_engine.dirty = 1;
}

static void engine_refresh_maneuver(const maneuver_state_t *state,double now) {
    if (state->icon == ICON_NONE || g_engine.current.icon == ICON_NONE) {
        engine_apply_maneuver(state,now);
        return;
    }
    if (g_engine.has_pending) {g_engine.pending = *state;}
    else {
        if (g_engine.has_next) {g_engine.next = *state;}
        else {g_engine.current = *state;}
    }
    render_invalidate_masks();
    g_engine.dirty = 1;
}

static void engine_set_progress(int level,int state,double now) {
    if(g_engine.has_pending) {
        g_pending_progress_level=level;g_pending_progress_state=state;
    } else cr_progress_set(&g_arrow,level,state,now);
    g_engine.dirty=1;
}

static void engine_promote_pending(double now) {
    g_engine.next=g_engine.pending;
    g_engine.has_next=1;g_engine.has_pending=0;
    cr_progress_begin_handoff(&g_arrow,now);
    cr_progress_set(&g_arrow,g_pending_progress_level,g_pending_progress_state,now);
    maneuver_start_push();g_engine.phase=ENGINE_PUSHING;
    render_invalidate_masks();g_engine.dirty=1;
}

static void engine_apply_presentation(void) {
    if (g_persp_deferred) {
        render_set_perspective(g_persp_deferred_value);
        g_persp_deferred = 0;
        g_engine.dirty = 1;
    }
}

static void clear_maneuver(void) {
    cr_lane_clear(&g_lane_decoder, &g_lane_guidance);
    cr_lane_panel_clear(g_lane_panel);
    render_reset_content_offset();
    memset(&g_engine.current, 0, sizeof(g_engine.current));
    g_engine.current.icon = ICON_APPROACH;
    g_engine.has_current = 1;
    g_engine.has_next    = 0;
    g_engine.has_pending = 0;
    g_engine.phase       = ENGINE_IDLE;
    maneuver_set_slide(1.0f);
    render_invalidate_masks();
    cr_progress_reset(&g_arrow);
    g_persp_deferred = 0;
    g_fade_active    = 0;
    g_fade_alpha     = 0.0f;
    render_set_global_alpha(0.0f);
    g_cleared        = 1;
    g_engine.dirty   = 1;
}

static void engine_tick(double now) {
    switch (g_engine.phase) {
    case ENGINE_PUSHING:
        if (!maneuver_is_pushing()) {
            if (g_engine.has_next) {
                g_engine.current = g_engine.next;
                cr_scene_t *old=g_engine.current_scene;
                g_engine.current_scene=g_engine.next_scene;g_engine.next_scene=old;
                g_engine.has_next = 0;
            }
            maneuver_commit_pushed_state(&g_engine.current);
            cr_progress_finish_handoff(&g_arrow,now);
            render_invalidate_masks();
            g_engine.dirty = 1;
            if (g_engine.has_pending) engine_promote_pending(now);
            else g_engine.phase = ENGINE_SLIDING_IN;
        }
        break;
    case ENGINE_SLIDING_IN:
        if (!maneuver_is_animating()) {
            if (g_engine.has_pending) engine_promote_pending(now);
            else g_engine.phase = ENGINE_IDLE;
        }
        break;
    case ENGINE_IDLE:
        break;
    }
    if (g_engine.phase == ENGINE_IDLE) engine_apply_presentation();
}

/* ================================================================
 * ChairPlay: commands, frames and the output texture
 * ================================================================ */

#define QUEUE 64
static cr_cmd_t g_queue[QUEUE];
static int g_queue_count;
static int g_ready;
static int g_needs_render = 1;
static int g_rendered_last;
static double g_previous_frame;
static GLuint g_out_fbo, g_out_tex;
static int g_out_w, g_out_h;

static void output_storage(int width, int height) {
    glBindTexture(GL_TEXTURE_2D, g_out_tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);
    g_out_w = width;
    g_out_h = height;
}

/*
 * The renderer was written as the only user of its context: it sets blending, depth testing and its
 * program once and expects them to stay. Here it shares the window's context with NanoVG and the video
 * picture, so each call swaps the two sets of state: the caller's is kept and given back at the end, the
 * renderer's own is kept from its previous call and put back at the start.
 */
typedef struct {
    GLint framebuffer, viewport[4], program, texture, active_texture, renderbuffer, depth_func, unpack;
    GLint blend_src_rgb, blend_dst_rgb, blend_src_alpha, blend_dst_alpha;
    GLboolean blend, depth, scissor, cull, stencil, depth_mask;
} gl_state_t;

typedef struct { gl_state_t caller; } gl_saved_t;

static gl_state_t g_own;
static int g_own_valid;

static void capture(gl_state_t *s) {
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &s->framebuffer);
    glGetIntegerv(GL_VIEWPORT, s->viewport);
    glGetIntegerv(GL_CURRENT_PROGRAM, &s->program);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &s->active_texture);
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &s->texture);
    glGetIntegerv(GL_RENDERBUFFER_BINDING, &s->renderbuffer);
    glGetIntegerv(GL_DEPTH_FUNC, &s->depth_func);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &s->unpack);
    glGetIntegerv(GL_BLEND_SRC_RGB, &s->blend_src_rgb);
    glGetIntegerv(GL_BLEND_DST_RGB, &s->blend_dst_rgb);
    glGetIntegerv(GL_BLEND_SRC_ALPHA, &s->blend_src_alpha);
    glGetIntegerv(GL_BLEND_DST_ALPHA, &s->blend_dst_alpha);
    glGetBooleanv(GL_DEPTH_WRITEMASK, &s->depth_mask);
    s->blend = glIsEnabled(GL_BLEND);
    s->depth = glIsEnabled(GL_DEPTH_TEST);
    s->scissor = glIsEnabled(GL_SCISSOR_TEST);
    s->cull = glIsEnabled(GL_CULL_FACE);
    s->stencil = glIsEnabled(GL_STENCIL_TEST);
}

static void set_enabled(GLenum cap, GLboolean on) {
    if (on) glEnable(cap); else glDisable(cap);
}

static void apply(const gl_state_t *s) {
    glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)s->framebuffer);
    glViewport(s->viewport[0], s->viewport[1], s->viewport[2], s->viewport[3]);
    glUseProgram((GLuint)s->program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, (GLuint)s->texture);
    glActiveTexture((GLenum)s->active_texture);
    glBindRenderbuffer(GL_RENDERBUFFER, (GLuint)s->renderbuffer);
    glDepthFunc((GLenum)s->depth_func);
    glPixelStorei(GL_UNPACK_ALIGNMENT, s->unpack);
    glBlendFuncSeparate((GLenum)s->blend_src_rgb, (GLenum)s->blend_dst_rgb,
                        (GLenum)s->blend_src_alpha, (GLenum)s->blend_dst_alpha);
    glDepthMask(s->depth_mask);
    set_enabled(GL_BLEND, s->blend);
    set_enabled(GL_DEPTH_TEST, s->depth);
    set_enabled(GL_SCISSOR_TEST, s->scissor);
    set_enabled(GL_CULL_FACE, s->cull);
    set_enabled(GL_STENCIL_TEST, s->stencil);
}

static void save_gl(gl_saved_t *s) {
    capture(&s->caller);
    cr_gl_begin();
    if (g_own_valid) {
        apply(&g_own);
    } else {
        /* Before render_init: the state a fresh context has, which is what the renderer starts from. */
        glDisable(GL_CULL_FACE);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_SCISSOR_TEST);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    }
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
}

static void restore_gl(const gl_saved_t *s) {
    capture(&g_own);
    g_own_valid = 1;
    cr_gl_end();
    apply(&s->caller);
}

unsigned cr_engine_init(int width, int height, const char **error) {
    gl_saved_t saved;
    const char *missing = NULL;
    unsigned char *flag;
    if (g_ready) return g_out_tex;
    if (width < 16 || height < 16) { if (error) *error = "size too small"; return 0; }
    if (!cr_gl_load(&missing)) { if (error) *error = missing; return 0; }
    save_gl(&saved);

    glGenTextures(1, &g_out_tex);
    output_storage(width, height);
    glGenFramebuffers(1, &g_out_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, g_out_fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_out_tex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        restore_gl(&saved);
        cr_engine_shutdown();
        if (error) *error = "output framebuffer incomplete";
        return 0;
    }

    {
        static maneuver_scene_provider_t provider;
        memset(&provider,0,sizeof(provider));
        provider.handles=scene_handles;provider.build_route=scene_route;
        provider.paint_masks=scene_paint;provider.elevation=scene_elevation;
        cr_scene_configure_provider(&provider);maneuver_set_scene_provider(&provider);
    }
    /* render_init takes the bound framebuffer as its window: ours. */
    if (render_init(width, height) < 0) {
        restore_gl(&saved);
        cr_engine_shutdown();
        if (error) *error = "render_init failed (shader compile or link; see the console)";
        return 0;
    }
    flag = cr_flag_atlas(FLAG_FRAME, FLAG_FRAMES);
    if (flag) {
        render_upload_flag_atlas(flag, FLAG_FRAME, FLAG_FRAME, FLAG_FRAMES);
        free(flag);
    }

    memset(&g_engine, 0, sizeof(g_engine));
    g_engine.current_scene=cr_scene_create();g_engine.next_scene=cr_scene_create();
    g_lane_panel=cr_lane_panel_create();
    if(!g_engine.current_scene || !g_engine.next_scene) {
        cr_scene_destroy(g_engine.current_scene);cr_scene_destroy(g_engine.next_scene);
        g_engine.current_scene=g_engine.next_scene=NULL; maneuver_set_scene_provider(NULL);
    }
    { extern float g_3d_offset_adjust;g_3d_offset_adjust=-.16f; }
    clear_maneuver();
    restore_gl(&saved);
    g_queue_count = 0;
    g_needs_render = 1;
    g_rendered_last = 0;
    g_ready = 1;
    return g_out_tex;
}

void cr_engine_resize(int width, int height) {
    gl_saved_t saved;
    if (!g_ready || width < 16 || height < 16 || (width == g_out_w && height == g_out_h)) return;
    save_gl(&saved);
    output_storage(width, height);
    render_set_viewport(width, height);
    restore_gl(&saved);
    g_needs_render = 1;
}

int cr_engine_command(const unsigned char *packet, int length) {
    if (!g_ready || length != CR_PKT_SIZE) return 0;
    if (g_queue_count == QUEUE) {
        /* As the TCP drain does, a later maneuver supersedes an earlier one: drop the oldest. */
        memmove(g_queue, g_queue + 1, sizeof(g_queue[0]) * (QUEUE - 1));
        g_queue_count--;
    }
    memcpy(&g_queue[g_queue_count++], packet, CR_PKT_SIZE);
    return 1;
}

int cr_engine_frame(double now) {
    gl_saved_t saved;
    int got_maneuver = 0, got_progress = 0, progress_level = 0, progress_mode = 0;
    int progress_state = CR_PROGRESS_OFF, dirty, i, status = 0;
    uint8_t pending_flags = 0, pending_perspective = 1;
    maneuver_state_t pending_maneuver;
    if (!g_ready) return 0;
    save_gl(&saved);

    /* Animations advance by elapsed time, in 30 fps frames; after idle the first frame is one step,
     * and a stall longer than 4 frames slows down instead of jumping (main.c). */
    {
        float step = 1.0f;
        if (g_rendered_last) {
            step = (float)((now - g_previous_frame) * TARGET_FPS);
            if (step < 0.0f) step = 0.0f;
            if (step > 4.0f) step = 4.0f;
        }
        g_previous_frame = now;
        render_set_frame_step(step);
    }

    memset(&pending_maneuver, 0, sizeof(pending_maneuver));
    for (i = 0; i < g_queue_count; i++) {
        const cr_cmd_t *cmd = &g_queue[i];
        if (cmd->cmd == CMD_LANES_BEGIN || cmd->cmd == CMD_LANES_LANE || cmd->cmd == CMD_LANES_COMMIT) {
            if (cr_lane_receive(&g_lane_decoder, cmd, &g_lane_guidance)) g_engine.dirty=1;
            continue;
        }
        switch (cmd->cmd) {
        case CMD_MANEUVER:
            cr_decode_maneuver(cmd, &pending_maneuver);
            pending_flags = cr_merge_maneuver_flags(got_maneuver, pending_flags, cmd->flags);
            pending_perspective = cmd->payload[43];
            got_progress=1;
            progress_level=(cmd->flags & MAN_FLAG_PROGRESS) ? cmd->payload[44] : 0;
            progress_mode=(cmd->flags & MAN_FLAG_PROGRESS) ? cmd->payload[45] : 0;
            progress_state=cr_progress_decode(cmd->flags,cmd->payload[42],progress_mode);
            got_maneuver = 1;
            break;
        case CMD_VISIBLE_AREA:
            if (render_set_visible_area((cmd->payload[0] << 8) | cmd->payload[1],
                    (cmd->payload[2] << 8) | cmd->payload[3],
                    (cmd->payload[4] << 8) | cmd->payload[5],
                    (cmd->payload[6] << 8) | cmd->payload[7]))
                g_engine.dirty = 1;
            break;
        case CMD_PERSPECTIVE:
            render_set_perspective(cmd->payload[0] ? 1 : 0);
            g_engine.dirty = 1;
            break;
        case CMD_PROGRESS:
            got_progress=1;
            progress_level=cmd->payload[0]; progress_mode=cmd->payload[1];
            progress_state=cr_progress_decode(cmd->flags,cmd->payload[2],progress_mode);
            break;
        case CMD_CLEAR:
            got_maneuver = 0;
            got_progress=0;
            clear_maneuver();
            break;
        default:
            break;
        }
    }
    g_queue_count = 0;

    if (got_maneuver) {
        int reveal_from_clear = g_cleared;
        if (reveal_from_clear) cr_progress_reset(&g_arrow);
        if (g_cleared) {
            g_cleared = 0;
            g_fade_alpha = 0.0f;
            g_fade_active = 1;
            render_set_global_alpha(0.0f);
        }
        if (pending_flags & MAN_FLAG_SET_PERSP) {
            g_persp_deferred = 1;
            g_persp_deferred_value = pending_perspective ? 1 : 0;
        }
        if (reveal_from_clear) {
            g_engine.current = pending_maneuver;
            g_engine.has_current = 1;
            g_engine.has_next = 0;
            g_engine.has_pending = 0;
            g_engine.phase = ENGINE_IDLE;
            maneuver_set_slide(1.0f);
            render_invalidate_masks();
            g_engine.dirty = 1;
            engine_apply_presentation();
        } else if (pending_flags & MAN_FLAG_REFRESH) {
            engine_refresh_maneuver(&pending_maneuver,now);
        } else {
            engine_apply_maneuver(&pending_maneuver,now);
        }
    }
    if(got_progress) {
        engine_set_progress(progress_level,progress_state,now);
        g_engine.dirty=1;
    }
    if(cr_progress_tick(&g_arrow,now)) g_engine.dirty=1;

    prepare_engine_scenes();
    engine_tick(now);
    prepare_engine_scenes();

    if (g_fade_active) {
        g_fade_alpha += FADE_SPEED * render_frame_step();
        if (g_fade_alpha >= 1.0f) {
            g_fade_alpha = 1.0f;
            g_fade_active = 0;
        }
        render_set_global_alpha(g_fade_alpha);
        g_engine.dirty = 1;
    }

    {
        cr_rect_t panel_target;
        float content_frame[3];
        render_get_visible_area(NULL,&panel_target);
        if(cr_lane_panel_update(g_lane_panel,&g_lane_guidance,panel_target.w,now))
            g_engine.dirty=1;
        cr_lane_panel_framing(g_lane_panel,g_engine.current_scene,
                             g_engine.has_next?g_engine.next_scene:NULL,content_frame);
        render_set_content_framing(content_frame[0],content_frame[1],content_frame[2]);
    }

    dirty = g_needs_render || g_engine.dirty || render_is_animating() || maneuver_needs_redraw()
        || cr_lane_panel_animating(g_lane_panel,now);
    g_engine.dirty = 0;
    g_rendered_last = dirty;
    if (dirty) {
        maneuver_state_t *next_ptr = g_engine.has_next ? &g_engine.next : NULL;
        cr_rect_t panel_visible;
        maneuver_prepare_frame(&g_engine.current, next_ptr);
        render_set_route_progress(g_arrow.fill,g_arrow.path_weight,g_arrow.glow);
        render_begin_frame();
        maneuver_draw(&g_engine.current, next_ptr);
        render_get_visible_area(&panel_visible,NULL);
        cr_lane_panel_draw(g_lane_panel,panel_visible,now);
        render_end_frame();
        g_needs_render = 0;
        status |= CR_ENGINE_RENDERED;
    }
    if (render_is_animating() || g_fade_active || cr_lane_panel_animating(g_lane_panel,now)
            || g_arrow.active || g_arrow.tint_active || g_arrow.handoff || !maneuver_is_presentationally_settled()
            || g_engine.phase != ENGINE_IDLE)
        status |= CR_ENGINE_ANIMATING;
    else if (maneuver_needs_redraw())
        status |= CR_ENGINE_LOOPING;
    if (!g_cleared) status |= CR_ENGINE_SHOWING;
    restore_gl(&saved);
    return status;
}

int cr_engine_read(unsigned char *rgba, int width, int height) {
    gl_saved_t saved;
    if (!g_ready || width != g_out_w || height != g_out_h) return 0;
    save_gl(&saved);
    glBindFramebuffer(GL_FRAMEBUFFER, g_out_fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    restore_gl(&saved);
    return 1;
}

void cr_engine_shutdown(void) {
    if (g_ready) {
        maneuver_set_scene_provider(NULL);
        cr_scene_destroy(g_engine.current_scene);cr_scene_destroy(g_engine.next_scene);
        g_engine.current_scene = g_engine.next_scene = NULL;
        cr_lane_panel_destroy(g_lane_panel);
        g_lane_panel = NULL;
        render_shutdown();
    }
    if (g_out_fbo) glDeleteFramebuffers(1, &g_out_fbo);
    if (g_out_tex) glDeleteTextures(1, &g_out_tex);
    g_out_fbo = g_out_tex = 0;
    g_out_w = g_out_h = 0;
    cr_gl_unload();
    g_ready = 0;
    g_own_valid = 0;
    g_queue_count = 0;
}
