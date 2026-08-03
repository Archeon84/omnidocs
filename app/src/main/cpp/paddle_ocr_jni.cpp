#include <jni.h>
#include <string>
#include <android/log.h>

#define TAG "PaddleOcrJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_omnidocs_app_ocr_PaddleNative_init(
    JNIEnv *env,
    jobject thiz,
    jstring det_model_path,
    jstring rec_model_path,
    jstring label_path,
    jint num_threads
) {
    LOGI("PaddleOCR init called");
    // TODO: Implement Paddle Lite initialization
    return JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_com_omnidocs_app_ocr_PaddleNative_recognize(
    JNIEnv *env,
    jobject thiz,
    jstring image_path,
    jfloat score_threshold
) {
    LOGI("PaddleOCR recognize called");
    // TODO: Implement OCR recognition
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_omnidocs_app_ocr_PaddleNative_destroy(
    JNIEnv *env,
    jobject thiz
) {
    LOGI("PaddleOCR destroy called");
    // TODO: Implement cleanup
}

} // extern "C"