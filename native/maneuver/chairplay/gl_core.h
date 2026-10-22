/*
 * ChairPlay: the GL the maneuver renderer sees, inside an OpenGL 3.3 core profile context on Windows.
 *
 * The renderer is written for OpenGL ES 2 and desktop GL 2.1: shaders only, no fixed function, but it
 * keeps its vertices in client memory and its shaders are GLSL 1.00/1.20. A core profile has neither,
 * so a few entry points are wrapped here and the drawing code stays as it is:
 *
 *   glVertexAttribPointer / glDrawArrays: the client arrays are copied into a stream buffer object
 *     at each draw, inside a vertex array object of our own (cr_gl_begin binds it);
 *   glShaderSource: the source becomes GLSL 3.30 core ("#version 330 core", in/out for
 *     attribute/varying, texture for texture2D, an out variable for gl_FragColor).
 *
 * opengl32.dll exports only GL 1.1; everything newer is loaded by cr_gl_load with the context current.
 *
 * Part of ChairPlay, under the GNU GPL v3 (LICENSE in the repository root).
 */
#ifndef CHAIRPLAY_GL_CORE_H
#define CHAIRPLAY_GL_CORE_H

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <GL/gl.h>
#include <GL/glext.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Entry points newer than GL 1.1, loaded by cr_gl_load. */
#define CR_GL_FUNCTIONS(X) \
    X(PFNGLACTIVETEXTUREPROC, glActiveTexture) \
    X(PFNGLBLENDFUNCSEPARATEPROC, glBlendFuncSeparate) \
    X(PFNGLCREATESHADERPROC, glCreateShader) \
    X(PFNGLSHADERSOURCEPROC, glShaderSource) \
    X(PFNGLCOMPILESHADERPROC, glCompileShader) \
    X(PFNGLGETSHADERIVPROC, glGetShaderiv) \
    X(PFNGLGETSHADERINFOLOGPROC, glGetShaderInfoLog) \
    X(PFNGLDELETESHADERPROC, glDeleteShader) \
    X(PFNGLCREATEPROGRAMPROC, glCreateProgram) \
    X(PFNGLATTACHSHADERPROC, glAttachShader) \
    X(PFNGLBINDATTRIBLOCATIONPROC, glBindAttribLocation) \
    X(PFNGLLINKPROGRAMPROC, glLinkProgram) \
    X(PFNGLGETPROGRAMIVPROC, glGetProgramiv) \
    X(PFNGLGETPROGRAMINFOLOGPROC, glGetProgramInfoLog) \
    X(PFNGLDELETEPROGRAMPROC, glDeleteProgram) \
    X(PFNGLUSEPROGRAMPROC, glUseProgram) \
    X(PFNGLGETATTRIBLOCATIONPROC, glGetAttribLocation) \
    X(PFNGLGETUNIFORMLOCATIONPROC, glGetUniformLocation) \
    X(PFNGLUNIFORM1FPROC, glUniform1f) \
    X(PFNGLUNIFORM2FPROC, glUniform2f) \
    X(PFNGLUNIFORM3FPROC, glUniform3f) \
    X(PFNGLUNIFORM4FPROC, glUniform4f) \
    X(PFNGLUNIFORM1IPROC, glUniform1i) \
    X(PFNGLUNIFORM3FVPROC, glUniform3fv) \
    X(PFNGLUNIFORM4FVPROC, glUniform4fv) \
    X(PFNGLUNIFORMMATRIX4FVPROC, glUniformMatrix4fv) \
    X(PFNGLVERTEXATTRIBPOINTERPROC, glVertexAttribPointer) \
    X(PFNGLENABLEVERTEXATTRIBARRAYPROC, glEnableVertexAttribArray) \
    X(PFNGLDISABLEVERTEXATTRIBARRAYPROC, glDisableVertexAttribArray) \
    X(PFNGLVERTEXATTRIB1FPROC, glVertexAttrib1f) \
    X(PFNGLGENFRAMEBUFFERSPROC, glGenFramebuffers) \
    X(PFNGLDELETEFRAMEBUFFERSPROC, glDeleteFramebuffers) \
    X(PFNGLBINDFRAMEBUFFERPROC, glBindFramebuffer) \
    X(PFNGLFRAMEBUFFERTEXTURE2DPROC, glFramebufferTexture2D) \
    X(PFNGLCHECKFRAMEBUFFERSTATUSPROC, glCheckFramebufferStatus) \
    X(PFNGLGENRENDERBUFFERSPROC, glGenRenderbuffers) \
    X(PFNGLDELETERENDERBUFFERSPROC, glDeleteRenderbuffers) \
    X(PFNGLBINDRENDERBUFFERPROC, glBindRenderbuffer) \
    X(PFNGLRENDERBUFFERSTORAGEPROC, glRenderbufferStorage) \
    X(PFNGLFRAMEBUFFERRENDERBUFFERPROC, glFramebufferRenderbuffer) \
    X(PFNGLGENBUFFERSPROC, glGenBuffers) \
    X(PFNGLDELETEBUFFERSPROC, glDeleteBuffers) \
    X(PFNGLBINDBUFFERPROC, glBindBuffer) \
    X(PFNGLBUFFERDATAPROC, glBufferData) \
    X(PFNGLBUFFERSUBDATAPROC, glBufferSubData) \
    X(PFNGLGENVERTEXARRAYSPROC, glGenVertexArrays) \
    X(PFNGLDELETEVERTEXARRAYSPROC, glDeleteVertexArrays) \
    X(PFNGLBINDVERTEXARRAYPROC, glBindVertexArray)

#define CR_GL_DECLARE(type, name) extern type cr_##name;
CR_GL_FUNCTIONS(CR_GL_DECLARE)
#undef CR_GL_DECLARE

/* Loads the entry points; the GL context must be current. Returns 1 when all were found, else 0 and names
 * the first missing one in *missing. */
int cr_gl_load(const char **missing);

/* Binds the renderer's vertex array object around a frame; cr_gl_end binds the caller's one again. */
void cr_gl_begin(void);
void cr_gl_end(void);

/* Frees the stream buffer and vertex array object; the context must be current. */
void cr_gl_unload(void);

/* The wrapped entry points (see the top of this file). */
void APIENTRY cr_shim_VertexAttribPointer(GLuint index, GLint size, GLenum type, GLboolean normalized,
                                          GLsizei stride, const void *pointer);
void APIENTRY cr_shim_EnableVertexAttribArray(GLuint index);
void APIENTRY cr_shim_DisableVertexAttribArray(GLuint index);
void APIENTRY cr_shim_VertexAttrib1f(GLuint index, GLfloat x);
void APIENTRY cr_shim_DrawArrays(GLenum mode, GLint first, GLsizei count);
void APIENTRY cr_shim_ShaderSource(GLuint shader, GLsizei count, const GLchar *const *strings, const GLint *lengths);

/* Translates GLSL 1.00/1.20 source to GLSL 3.30 core for a vertex (fragment = 0) or fragment shader.
 * Returns a malloc'ed string the caller frees, or NULL when out of memory. Pure; tested on its own. */
char *cr_glsl_to_core(const char *source, int fragment);

#ifdef __cplusplus
}
#endif

/* The renderer's code calls the GL by its usual names. */
#ifndef CR_GL_CORE_IMPLEMENTATION
#define glActiveTexture cr_glActiveTexture
#define glBlendFuncSeparate cr_glBlendFuncSeparate
#define glCreateShader cr_glCreateShader
#define glShaderSource cr_shim_ShaderSource
#define glCompileShader cr_glCompileShader
#define glGetShaderiv cr_glGetShaderiv
#define glGetShaderInfoLog cr_glGetShaderInfoLog
#define glDeleteShader cr_glDeleteShader
#define glCreateProgram cr_glCreateProgram
#define glAttachShader cr_glAttachShader
#define glBindAttribLocation cr_glBindAttribLocation
#define glLinkProgram cr_glLinkProgram
#define glGetProgramiv cr_glGetProgramiv
#define glGetProgramInfoLog cr_glGetProgramInfoLog
#define glDeleteProgram cr_glDeleteProgram
#define glUseProgram cr_glUseProgram
#define glGetAttribLocation cr_glGetAttribLocation
#define glGetUniformLocation cr_glGetUniformLocation
#define glUniform1f cr_glUniform1f
#define glUniform2f cr_glUniform2f
#define glUniform3f cr_glUniform3f
#define glUniform4f cr_glUniform4f
#define glUniform1i cr_glUniform1i
#define glUniform3fv cr_glUniform3fv
#define glUniform4fv cr_glUniform4fv
#define glUniformMatrix4fv cr_glUniformMatrix4fv
#define glVertexAttribPointer cr_shim_VertexAttribPointer
#define glEnableVertexAttribArray cr_shim_EnableVertexAttribArray
#define glDisableVertexAttribArray cr_shim_DisableVertexAttribArray
#define glVertexAttrib1f cr_shim_VertexAttrib1f
#define glDrawArrays cr_shim_DrawArrays
#define glGenFramebuffers cr_glGenFramebuffers
#define glDeleteFramebuffers cr_glDeleteFramebuffers
#define glBindFramebuffer cr_glBindFramebuffer
#define glFramebufferTexture2D cr_glFramebufferTexture2D
#define glCheckFramebufferStatus cr_glCheckFramebufferStatus
#define glGenRenderbuffers cr_glGenRenderbuffers
#define glDeleteRenderbuffers cr_glDeleteRenderbuffers
#define glBindRenderbuffer cr_glBindRenderbuffer
#define glRenderbufferStorage cr_glRenderbufferStorage
#define glFramebufferRenderbuffer cr_glFramebufferRenderbuffer
#define glGenBuffers cr_glGenBuffers
#define glDeleteBuffers cr_glDeleteBuffers
#define glBindBuffer cr_glBindBuffer
#define glBufferData cr_glBufferData
#define glBufferSubData cr_glBufferSubData
#define glGenVertexArrays cr_glGenVertexArrays
#define glDeleteVertexArrays cr_glDeleteVertexArrays
#define glBindVertexArray cr_glBindVertexArray
#endif

#endif
