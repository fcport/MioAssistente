#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "MioAssistenteWhisper"

JNIEXPORT jlong JNICALL
Java_com_example_mioassistente_Whisper_nativeInit(JNIEnv *env, jclass cls, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT jstring JNICALL
Java_com_example_mioassistente_Whisper_nativeTranscribe(JNIEnv *env, jclass cls, jlong handle,
                                                        jfloatArray audio, jint threads, jint audioCtx) {
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) handle;
    if (!ctx) return NULL;
    jsize n = (*env)->GetArrayLength(env, audio);
    jfloat *a = (*env)->GetFloatArrayElements(env, audio, NULL);

    struct whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads = threads;
    wp.language = "it";
    wp.translate = false;
    wp.no_context = true;
    wp.no_timestamps = true;
    wp.single_segment = true;
    wp.suppress_blank = true;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;
    if (audioCtx > 0) wp.audio_ctx = audioCtx;

    int r = whisper_full(ctx, wp, a, n);
    (*env)->ReleaseFloatArrayElements(env, audio, a, JNI_ABORT);
    if (r != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full error %d", r);
        return NULL;
    }
    char out[8192];
    out[0] = 0;
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        if (t && strlen(out) + strlen(t) < sizeof(out) - 1) strcat(out, t);
    }
    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT void JNICALL
Java_com_example_mioassistente_Whisper_nativeFree(JNIEnv *env, jclass cls, jlong handle) {
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) handle;
    if (ctx) whisper_free(ctx);
}
