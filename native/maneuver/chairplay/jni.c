/*
 * ChairPlay: JNI entry points of chairplay_maneuver.dll, for io.github.numberonebot.chairplay.hud.maneuver.ManeuverNative.
 *
 * Part of ChairPlay, under the GNU GPL v3 (LICENSE in the repository root).
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "engine.h"
#include "gl_core.h"
#include "stderr_capture.h"

#define FN(name) Java_io_github_numberonebot_chairplay_hud_maneuver_ManeuverNative_##name

static const char *g_error = "";

JNIEXPORT jint JNICALL FN(init)(JNIEnv *env, jclass cls, jint width, jint height) {
    const char *error = "unknown";
    unsigned texture;
    (void)env; (void)cls;
    texture = cr_engine_init(width, height, &error);
    g_error = texture ? "" : error;
    return (jint)texture;
}

JNIEXPORT jstring JNICALL FN(lastError)(JNIEnv *env, jclass cls) {
    (void)cls;
    return (*env)->NewStringUTF(env, g_error ? g_error : "");
}

JNIEXPORT void JNICALL FN(resize)(JNIEnv *env, jclass cls, jint width, jint height) {
    (void)env; (void)cls;
    cr_engine_resize(width, height);
}

JNIEXPORT jboolean JNICALL FN(flagAtlas)(JNIEnv *env, jclass cls, jobject buffer, jint frameWidth, jint frameHeight,
                                         jint frames) {
    void *pixels;
    (void)cls;
    if (!buffer || frameWidth <= 0 || frameHeight <= 0 || frames <= 0) return JNI_FALSE;
    pixels = (*env)->GetDirectBufferAddress(env, buffer);
    if (!pixels || (*env)->GetDirectBufferCapacity(env, buffer) < (jlong)frameWidth * frames * frameHeight * 4) return JNI_FALSE;
    return cr_engine_flag_atlas((const unsigned char *)pixels, frameWidth, frameHeight, frames) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN(command)(JNIEnv *env, jclass cls, jbyteArray packet) {
    jbyte bytes[64];
    jsize length;
    (void)cls;
    if (!packet) return JNI_FALSE;
    length = (*env)->GetArrayLength(env, packet);
    if (length <= 0 || length > (jsize)sizeof(bytes)) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, packet, 0, length, bytes);
    return cr_engine_command((const unsigned char *)bytes, (int)length) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL FN(frame)(JNIEnv *env, jclass cls, jdouble now) {
    (void)env; (void)cls;
    return (jint)cr_engine_frame((double)now);
}

JNIEXPORT jboolean JNICALL FN(read)(JNIEnv *env, jclass cls, jint width, jint height, jobject buffer) {
    void *pixels;
    (void)cls;
    if (!buffer || width <= 0 || height <= 0) return JNI_FALSE;
    pixels = (*env)->GetDirectBufferAddress(env, buffer);
    if (!pixels || (*env)->GetDirectBufferCapacity(env, buffer) < (jlong)width * height * 4) return JNI_FALSE;
    return cr_engine_read((unsigned char *)pixels, width, height) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL FN(shutdown)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    cr_engine_shutdown();
}

JNIEXPORT jstring JNICALL FN(glslToCore)(JNIEnv *env, jclass cls, jstring source, jboolean fragment) {
    const char *utf;
    char *core;
    jstring result;
    (void)cls;
    if (!source) return NULL;
    utf = (*env)->GetStringUTFChars(env, source, NULL);
    if (!utf) return NULL;
    core = cr_glsl_to_core(utf, fragment == JNI_TRUE);
    (*env)->ReleaseStringUTFChars(env, source, utf);
    if (!core) return NULL;
    result = (*env)->NewStringUTF(env, core);
    free(core);
    return result;
}

/* The renderer's fprintf(stderr, ...) text since the last call (stderr_capture.h), or null when there is none. */
JNIEXPORT jstring JNICALL FN(drainLog)(JNIEnv *env, jclass cls) {
    static char text[16 * 1024 + 1];
    (void)cls;
    if (chairplay_stderr_take(text, (int)sizeof text) == 0) return NULL;
    return (*env)->NewStringUTF(env, text);
}

/* For the tests: writes text to stderr as the renderer does. */
JNIEXPORT void JNICALL FN(logSelfTest)(JNIEnv *env, jclass cls, jstring text) {
    const char *utf;
    (void)cls;
    if (!text) return;
    utf = (*env)->GetStringUTFChars(env, text, NULL);
    if (!utf) return;
    fprintf(stderr, "%s", utf);
    (*env)->ReleaseStringUTFChars(env, text, utf);
}
