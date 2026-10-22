/*
 * ChairPlay: GL loader and the core-profile wrappers described in gl_core.h.
 *
 * Part of ChairPlay, under the GNU GPL v3 (LICENSE in the repository root).
 */
#define CR_GL_CORE_IMPLEMENTATION
#include "gl_core.h"

#include <stdlib.h>
#include <string.h>

#define CR_GL_DEFINE(type, name) type cr_##name;
CR_GL_FUNCTIONS(CR_GL_DEFINE)
#undef CR_GL_DEFINE

static void *proc(const char *name) {
    void *p = (void *)wglGetProcAddress(name);
    /* Some drivers return small values instead of NULL for a missing entry point. */
    if (p == NULL || p == (void *)1 || p == (void *)2 || p == (void *)3 || p == (void *)-1) {
        HMODULE gl = GetModuleHandleA("opengl32.dll");
        p = gl ? (void *)GetProcAddress(gl, name) : NULL;
    }
    return p;
}

int cr_gl_load(const char **missing) {
#define CR_GL_LOAD(type, name) \
    cr_##name = (type)proc(#name); \
    if (!cr_##name) { if (missing) *missing = #name; return 0; }
    CR_GL_FUNCTIONS(CR_GL_LOAD)
#undef CR_GL_LOAD
    return 1;
}

/* ---- client arrays ---- */

#define MAX_ATTRIBS 16

typedef struct {
    GLint size;
    GLenum type;
    GLboolean normalized;
    GLsizei stride;
    const void *pointer;
    int enabled;
} client_attrib_t;

static client_attrib_t g_attribs[MAX_ATTRIBS];
static GLuint g_vao, g_stream;
static GLint g_caller_vao;
/* The stream buffer's size and how much of it is written; it starts full, so the first draw allocates it. */
static GLsizeiptr g_stream_size = 1 << 22, g_stream_used = 1 << 22;

static int type_bytes(GLenum type) {
    switch (type) {
    case GL_BYTE: case GL_UNSIGNED_BYTE: return 1;
    case GL_SHORT: case GL_UNSIGNED_SHORT: case GL_HALF_FLOAT: return 2;
    default: return 4;
    }
}

void cr_gl_begin(void) {
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &g_caller_vao);
    if (!g_vao) {
        cr_glGenVertexArrays(1, &g_vao);
        cr_glGenBuffers(1, &g_stream);
    }
    cr_glBindVertexArray(g_vao);
}

void cr_gl_end(void) {
    cr_glBindBuffer(GL_ARRAY_BUFFER, 0);
    cr_glBindVertexArray((GLuint)g_caller_vao);
}

void cr_gl_unload(void) {
    if (g_stream) cr_glDeleteBuffers(1, &g_stream);
    if (g_vao) cr_glDeleteVertexArrays(1, &g_vao);
    g_stream = g_vao = 0;
    g_stream_used = g_stream_size;
    memset(g_attribs, 0, sizeof(g_attribs));
}

void APIENTRY cr_shim_VertexAttribPointer(GLuint index, GLint size, GLenum type, GLboolean normalized,
                                          GLsizei stride, const void *pointer) {
    /* The renderer passes glGetAttribLocation's -1 for attributes a program does not use. */
    if (index >= MAX_ATTRIBS) return;
    g_attribs[index].size = size;
    g_attribs[index].type = type;
    g_attribs[index].normalized = normalized;
    g_attribs[index].stride = stride;
    g_attribs[index].pointer = pointer;
}

void APIENTRY cr_shim_EnableVertexAttribArray(GLuint index) {
    if (index >= MAX_ATTRIBS) return;
    g_attribs[index].enabled = 1;
    cr_glEnableVertexAttribArray(index);
}

void APIENTRY cr_shim_DisableVertexAttribArray(GLuint index) {
    if (index >= MAX_ATTRIBS) return;
    g_attribs[index].enabled = 0;
    cr_glDisableVertexAttribArray(index);
}

void APIENTRY cr_shim_VertexAttrib1f(GLuint index, GLfloat x) {
    if (index >= MAX_ATTRIBS) return;
    cr_glVertexAttrib1f(index, x);
}

void APIENTRY cr_shim_DrawArrays(GLenum mode, GLint first, GLsizei count) {
    GLsizeiptr offsets[MAX_ATTRIBS], bytes[MAX_ATTRIBS], total = 0;
    int i;
    if (count <= 0 || first < 0) return;
    for (i = 0; i < MAX_ATTRIBS; i++) {
        const client_attrib_t *a = &g_attribs[i];
        GLsizei element = a->size * type_bytes(a->type);
        GLsizei stride = a->stride ? a->stride : element;
        bytes[i] = 0;
        if (!a->enabled || !a->pointer) continue;
        /* Everything up to the last vertex drawn, so the attribute keeps its own offsets. */
        bytes[i] = (GLsizeiptr)(first + count - 1) * stride + element;
        offsets[i] = total;
        total += (bytes[i] + 15) & ~(GLsizeiptr)15;
    }
    if (total == 0) return;
    cr_glBindBuffer(GL_ARRAY_BUFFER, g_stream);
    /* The draws append to one stream buffer; when it is full a fresh store replaces it, so the driver never
     * waits for earlier draws to finish with the part being written. */
    if (total > g_stream_size || g_stream_used + total > g_stream_size) {
        while (total > g_stream_size) g_stream_size *= 2;
        cr_glBufferData(GL_ARRAY_BUFFER, g_stream_size, NULL, GL_STREAM_DRAW);
        g_stream_used = 0;
    }
    for (i = 0; i < MAX_ATTRIBS; i++) {
        const client_attrib_t *a = &g_attribs[i];
        GLsizeiptr at = g_stream_used + offsets[i];
        if (!bytes[i]) continue;
        cr_glBufferSubData(GL_ARRAY_BUFFER, at, bytes[i], a->pointer);
        cr_glVertexAttribPointer((GLuint)i, a->size, a->type, a->normalized, a->stride, (const void *)at);
    }
    g_stream_used += total;
    glDrawArrays(mode, first, count);
}

/* ---- GLSL ---- */

static int ident_char(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
}

/* Appends src to the growing buffer; returns 0 when out of memory. */
static int append(char **out, size_t *length, size_t *capacity, const char *src, size_t n) {
    if (*length + n + 1 > *capacity) {
        size_t wanted = (*capacity ? *capacity * 2 : 1024);
        char *grown;
        while (wanted < *length + n + 1) wanted *= 2;
        grown = (char *)realloc(*out, wanted);
        if (!grown) return 0;
        *out = grown;
        *capacity = wanted;
    }
    memcpy(*out + *length, src, n);
    *length += n;
    (*out)[*length] = 0;
    return 1;
}

char *cr_glsl_to_core(const char *source, int fragment) {
    static const char *const from[] = { "attribute", "varying", "texture2D", "gl_FragColor" };
    const char *to[4];
    char *out = NULL;
    size_t length = 0, capacity = 0;
    const char *p = source;
    const char *header = fragment ? "#version 330 core\nout vec4 cr_FragColor;\n" : "#version 330 core\n";
    to[0] = "in";
    to[1] = fragment ? "in" : "out";
    to[2] = "texture";
    to[3] = "cr_FragColor";
    if (!append(&out, &length, &capacity, header, strlen(header))) return NULL;
    while (*p) {
        if (ident_char(*p)) {
            const char *start = p;
            size_t n;
            int k, replaced = 0;
            while (ident_char(*p)) p++;
            n = (size_t)(p - start);
            for (k = 0; k < 4; k++) {
                if (strlen(from[k]) == n && strncmp(start, from[k], n) == 0) {
                    if (!append(&out, &length, &capacity, to[k], strlen(to[k]))) { free(out); return NULL; }
                    replaced = 1;
                    break;
                }
            }
            if (!replaced && !append(&out, &length, &capacity, start, n)) { free(out); return NULL; }
        } else {
            if (!append(&out, &length, &capacity, p, 1)) { free(out); return NULL; }
            p++;
        }
    }
    return out;
}

void APIENTRY cr_shim_ShaderSource(GLuint shader, GLsizei count, const GLchar *const *strings, const GLint *lengths) {
    char *joined = NULL, *core;
    size_t length = 0, capacity = 0;
    GLint type = 0;
    GLsizei i;
    for (i = 0; i < count; i++) {
        size_t n = (lengths && lengths[i] >= 0) ? (size_t)lengths[i] : strlen(strings[i]);
        if (!append(&joined, &length, &capacity, strings[i], n)) { free(joined); return; }
    }
    if (!joined) return;
    cr_glGetShaderiv(shader, GL_SHADER_TYPE, &type);
    core = cr_glsl_to_core(joined, type == GL_FRAGMENT_SHADER);
    free(joined);
    if (!core) return;
    {
        const GLchar *source = core;
        cr_glShaderSource(shader, 1, &source, NULL);
    }
    free(core);
}
