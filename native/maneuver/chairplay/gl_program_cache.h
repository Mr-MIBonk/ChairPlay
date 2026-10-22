/*
 * ChairPlay: stands in for the MHI2Q project's common/gl_program_cache.h, which keeps compiled shader
 * binaries on the head unit's flash. A desktop driver caches shaders itself, so this always compiles,
 * as that header does for its macOS build.
 *
 * Part of ChairPlay, under the GNU GPL v3 (LICENSE in the repository root).
 */
#ifndef GL_PROGRAM_CACHE_H
#define GL_PROGRAM_CACHE_H

static inline int glpc_load(GLuint p, const char *t, const char *v, const char *f)
{ (void)p; (void)t; (void)v; (void)f; return 0; }
static inline void glpc_store(GLuint p, const char *t, const char *v, const char *f)
{ (void)p; (void)t; (void)v; (void)f; }

#endif
