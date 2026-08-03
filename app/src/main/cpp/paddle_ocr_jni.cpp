#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <android/log.h>
#include <fstream>
#include "paddle_api.h"

#define TAG "PaddleOcrJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace paddle::lite_api;

// Global state
static std::shared_ptr<PaddlePredictor> g_det_predictor = nullptr;
static std::shared_ptr<PaddlePredictor> g_rec_predictor = nullptr;
static std::vector<std::string> g_labels;
static int g_num_threads = 4;
static bool g_initialized = false;

// ---- Minimal BMP/PPM image loader (no external deps) ----
// We decode JPEG via Android BitmapFactory through JNI callback.

struct ImageData {
    std::vector<uint8_t> rgb;  // RGB pixels
    int width;
    int height;
};

static ImageData loadImageViaAndroid(JNIEnv* env, const char* path) {
    ImageData img{ {}, 0, 0 };

    // Find the BitmapHelper class
    jclass cls = env->FindClass("com/omnidocs/app/ocr/BitmapHelper");
    if (!cls) {
        LOGE("BitmapHelper class not found");
        return img;
    }

    jmethodID mid = env->GetStaticMethodID(cls, "loadImage",
        "(Ljava/lang/String;)[I");
    if (!mid) {
        LOGE("loadImage method not found");
        return img;
    }

    jstring jpath = env->NewStringUTF(path);
    jintArray result = (jintArray)env->CallStaticObjectMethod(cls, mid, jpath);
    env->DeleteLocalRef(jpath);

    if (!result) return img;

    jsize len = env->GetArrayLength(result);
    if (len < 2) return img;

    jint* data = env->GetIntArrayElements(result, nullptr);
    img.width = data[0];
    img.height = data[1];
    img.rgb.resize((len - 2) * 4);
    memcpy(img.rgb.data(), data + 2, (len - 2) * 4);
    env->ReleaseIntArrayElements(result, data, JNI_ABORT);
    env->DeleteLocalRef(result);

    return img;
}

// ---- Image preprocessing for PaddleOCR ----
// Resize image using bilinear interpolation
static std::vector<uint8_t> resizeBilinear(const uint8_t* src, int srcW, int srcH,
                                           int dstW, int dstH, int channels) {
    std::vector<uint8_t> dst(dstW * dstH * channels);
    float xRatio = (float)srcW / dstW;
    float yRatio = (float)srcH / dstH;

    for (int y = 0; y < dstH; y++) {
        for (int x = 0; x < dstW; x++) {
            float srcX = x * xRatio;
            float srcY = y * yRatio;
            int x0 = (int)srcX, y0 = (int)srcY;
            int x1 = std::min(x0 + 1, srcW - 1);
            int y1 = std::min(y0 + 1, srcH - 1);
            float fx = srcX - x0, fy = srcY - y0;

            for (int c = 0; c < channels; c++) {
                float v = (1-fx)*(1-fy)*src[(y0*srcW+x0)*channels+c]
                        + fx*(1-fy)*src[(y0*srcW+x1)*channels+c]
                        + (1-fx)*fy*src[(y1*srcW+x0)*channels+c]
                        + fx*fy*src[(y1*srcW+x1)*channels+c];
                dst[(y*dstW+x)*channels+c] = (uint8_t)std::max(0.f, std::min(255.f, v));
            }
        }
    }
    return dst;
}

// Fill a Paddle Lite tensor from RGB image, with normalization.
// PaddleOCR expects: (pixel / 255 - mean) / std, NCHW layout.
static void fillDetTensor(Tensor* tensor, const uint8_t* rgb, int w, int h) {
    tensor->Resize({1, 3, (int64_t)h, (int64_t)w});
    float* data = tensor->mutable_data<float>();

    float mean[3] = {0.485f, 0.456f, 0.406f};
    float scale[3] = {1.0f / 0.229f, 1.0f / 0.224f, 1.0f / 0.225f};

    for (int c = 0; c < 3; c++) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float val = rgb[(y * w + x) * 3 + c] / 255.0f;
                data[c * h * w + y * w + x] = (val - mean[c]) * scale[c];
            }
        }
    }
}

static void fillRecTensor(Tensor* tensor, const uint8_t* rgb, int w, int h) {
    tensor->Resize({1, 3, 48, (int64_t)w});
    float* data = tensor->mutable_data<float>();

    float mean[3] = {0.5f, 0.5f, 0.5f};
    float scale[3] = {2.0f, 2.0f, 2.0f};

    for (int c = 0; c < 3; c++) {
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < w; x++) {
                int sy = std::min(y * h / 48, h - 1);
                float val = rgb[(sy * w + x) * 3 + c] / 255.0f;
                data[c * 48 * w + y * w + x] = (val - mean[c]) * scale[c];
            }
        }
    }
}

// ---- Detection post-processing: extract boxes from score map ----
struct Box {
    std::vector<int> pts;  // [x1,y1, x2,y2, x3,y3, x4,y4]
    float score;
};

static std::vector<Box> postProcessDetection(const float* scoreMap,
                                               int mapW, int mapH,
                                               int imgW, int imgH,
                                               float threshold) {
    std::vector<Box> boxes;

    // Simple approach: find connected components via thresholding
    std::vector<bool> visited(mapW * mapH, false);
    float xScale = (float)imgW / mapW;
    float yScale = (float)imgH / mapH;

    for (int y = 0; y < mapH; y++) {
        for (int x = 0; x < mapW; x++) {
            int idx = y * mapW + x;
            if (visited[idx] || scoreMap[idx] < threshold) continue;

            // Flood fill to find connected region
            int minX = x, maxX = x, minY = y, maxY = y;
            float sumScore = 0;
            int count = 0;
            std::vector<int> stack = {idx};
            visited[idx] = true;

            while (!stack.empty()) {
                int ci = stack.back();
                stack.pop_back();
                int cx = ci % mapW;
                int cy = ci / mapW;
                minX = std::min(minX, cx);
                maxX = std::max(maxX, cx);
                minY = std::min(minY, cy);
                maxY = std::max(maxY, cy);
                sumScore += scoreMap[ci];
                count++;

                // 4-connected neighbors
                int dx[] = {-1, 1, 0, 0};
                int dy[] = {0, 0, -1, 1};
                for (int d = 0; d < 4; d++) {
                    int nx = cx + dx[d], ny = cy + dy[d];
                    if (nx >= 0 && nx < mapW && ny >= 0 && ny < mapH) {
                        int ni = ny * mapW + nx;
                        if (!visited[ni] && scoreMap[ni] >= threshold) {
                            visited[ni] = true;
                            stack.push_back(ni);
                        }
                    }
                }
            }

            // Filter small regions
            int boxW = maxX - minX + 1;
            int boxH = maxY - minY + 1;
            if (boxW < 3 || boxH < 3) continue;

            // Scale to original image coordinates with padding
            int pad = 2;
            int ix1 = std::max(0, (int)(minX * xScale) - pad);
            int iy1 = std::max(0, (int)(minY * yScale) - pad);
            int ix2 = std::min(imgW - 1, (int)(maxX * xScale) + pad);
            int iy2 = std::min(imgH - 1, (int)(maxY * yScale) + pad);

            // Expand height for better recognition (text lines need vertical room)
            int expandH = std::max(0, (int)((boxH * yScale * 0.15f)));
            iy1 = std::max(0, iy1 - expandH);
            iy2 = std::min(imgH - 1, iy2 + expandH);

            Box box;
            box.pts = {ix1, iy1, ix2, iy1, ix2, iy2, ix1, iy2};
            box.score = sumScore / count;
            boxes.push_back(box);
        }
    }

    // Sort top-to-bottom, left-to-right
    std::sort(boxes.begin(), boxes.end(), [](const Box& a, const Box& b) {
        int ay = (a.pts[1] + a.pts[5]) / 2;
        int by = (b.pts[1] + b.pts[5]) / 2;
        if (std::abs(ay - by) < 15) return a.pts[0] < b.pts[0];
        return ay < by;
    });

    return boxes;
}

// ---- Recognition: CTC decode ----
static std::string ctcDecode(const float* output, int seqLen, int numClasses) {
    std::string text;
    int prevIdx = -1;

    // Load label map
    for (int t = 0; t < seqLen; t++) {
        int bestIdx = 0;
        float bestVal = output[t * numClasses];
        for (int c = 1; c < numClasses; c++) {
            if (output[t * numClasses + c] > bestVal) {
                bestVal = output[t * numClasses + c];
                bestIdx = c;
            }
        }
        // Index 0 is blank in CTC, skip repeats
        if (bestIdx != 0 && bestIdx != prevIdx) {
            if (bestIdx - 1 < (int)g_labels.size()) {
                text += g_labels[bestIdx - 1];
            }
        }
        prevIdx = bestIdx;
    }
    return text;
}

// ---- Load labels file ----
static bool loadLabels(const char* path) {
    g_labels.clear();
    std::ifstream file(path);
    if (!file.is_open()) {
        LOGE("Failed to open label file: %s", path);
        return false;
    }
    std::string line;
    while (std::getline(file, line)) {
        // Remove trailing \r
        if (!line.empty() && line.back() == '\r') line.pop_back();
        g_labels.push_back(line);
    }
    LOGI("Loaded %d labels", (int)g_labels.size());
    return !g_labels.empty();
}

// ---- JNI functions ----

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

    if (g_initialized) {
        LOGI("Already initialized, destroying first");
        // Destroy existing predictors
        g_det_predictor.reset();
        g_rec_predictor.reset();
        g_initialized = false;
    }

    const char* detPath = env->GetStringUTFChars(det_model_path, nullptr);
    const char* recPath = env->GetStringUTFChars(rec_model_path, nullptr);
    const char* lblPath = env->GetStringUTFChars(label_path, nullptr);
    g_num_threads = num_threads;

    LOGI("Det model: %s", detPath);
    LOGI("Rec model: %s", recPath);
    LOGI("Labels: %s", lblPath);

    // Load labels
    if (!loadLabels(lblPath)) {
        LOGE("Failed to load labels");
        env->ReleaseStringUTFChars(det_model_path, detPath);
        env->ReleaseStringUTFChars(rec_model_path, recPath);
        env->ReleaseStringUTFChars(label_path, lblPath);
        return JNI_FALSE;
    }

    // Create detection predictor
    try {
        MobileConfig detConfig;
        detConfig.set_model_from_file(detPath);
        detConfig.set_threads(g_num_threads);
        g_det_predictor = CreatePaddlePredictor(detConfig);
        LOGI("Detection predictor created");
    } catch (...) {
        LOGE("Failed to create detection predictor");
        env->ReleaseStringUTFChars(det_model_path, detPath);
        env->ReleaseStringUTFChars(rec_model_path, recPath);
        env->ReleaseStringUTFChars(label_path, lblPath);
        return JNI_FALSE;
    }

    // Create recognition predictor
    try {
        MobileConfig recConfig;
        recConfig.set_model_from_file(recPath);
        recConfig.set_threads(g_num_threads);
        g_rec_predictor = CreatePaddlePredictor(recConfig);
        LOGI("Recognition predictor created");
    } catch (...) {
        LOGE("Failed to create recognition predictor");
        g_det_predictor.reset();
        env->ReleaseStringUTFChars(det_model_path, detPath);
        env->ReleaseStringUTFChars(rec_model_path, recPath);
        env->ReleaseStringUTFChars(label_path, lblPath);
        return JNI_FALSE;
    }

    g_initialized = true;
    LOGI("PaddleOCR initialized successfully");

    env->ReleaseStringUTFChars(det_model_path, detPath);
    env->ReleaseStringUTFChars(rec_model_path, recPath);
    env->ReleaseStringUTFChars(label_path, lblPath);

    return JNI_TRUE;
}

JNIEXPORT jobject JNICALL
Java_com_omnidocs_app_ocr_PaddleNative_recognize(
    JNIEnv *env,
    jobject thiz,
    jstring image_path,
    jfloat score_threshold
) {
    LOGI("PaddleOCR recognize called");

    if (!g_initialized || !g_det_predictor || !g_rec_predictor) {
        LOGE("Not initialized");
        return nullptr;
    }

    const char* imgPath = env->GetStringUTFChars(image_path, nullptr);

    // Load image via Android BitmapFactory
    ImageData image = loadImageViaAndroid(env, imgPath);
    env->ReleaseStringUTFChars(image_path, imgPath);

    if (image.rgb.empty() || image.width == 0 || image.height == 0) {
        LOGE("Failed to load image");
        return nullptr;
    }

    LOGI("Image loaded: %dx%d", image.width, image.height);

    int origW = image.width;
    int origH = image.height;

    // --- Detection stage ---
    // Resize to 960x960 (PaddleOCR default det input)
    int detInputSize = 960;
    auto resized = resizeBilinear(image.rgb.data(), origW, origH,
                                  detInputSize, detInputSize, 3);

    // Fill detection tensor
    auto detInput = g_det_predictor->GetInput(0);
    fillDetTensor(detInput.get(), resized.data(), detInputSize, detInputSize);

    // Run detection
    try {
        g_det_predictor->Run();
    } catch (...) {
        LOGE("Detection inference failed");
        return nullptr;
    }

    // Get detection output
    auto detOutput = g_det_predictor->GetOutput(0);
    auto detShape = detOutput->shape();
    const float* detData = detOutput->data<float>();

    LOGI("Det output shape: %d %d %d %d",
         (int)detShape[0], (int)detShape[1],
         (int)detShape[2], (int)detShape[3]);

    int mapH = detShape[2];
    int mapW = detShape[3];

    // Extract boxes
    auto boxes = postProcessDetection(detData, mapW, mapH, origW, origH, score_threshold);
    LOGI("Found %d text regions", (int)boxes.size());

    if (boxes.empty()) {
        return nullptr;
    }

    // --- Recognition stage ---
    jclass floatArrayClass = env->FindClass("[F");
    jobjectArray jresult = env->NewObjectArray(boxes.size(), floatArrayClass, nullptr);

    for (int i = 0; i < (int)boxes.size(); i++) {
        auto& box = boxes[i];
        int bx1 = box.pts[0], by1 = box.pts[1];
        int bx2 = box.pts[4], by2 = box.pts[5];
        int cropW = bx2 - bx1;
        int cropH = by2 - by1;

        if (cropW <= 0 || cropH <= 0) continue;

        // Crop from original image
        std::vector<uint8_t> cropped(cropW * cropH * 3);
        for (int cy = 0; cy < cropH; cy++) {
            int srcY = std::min(by1 + cy, origH - 1);
            for (int cx = 0; cx < cropW; cx++) {
                int srcX = std::min(bx1 + cx, origW - 1);
                memcpy(&cropped[(cy * cropW + cx) * 3],
                       &image.rgb[(srcY * origW + srcX) * 3], 3);
            }
        }

        // Resize to height=48, proportional width
        int recH = 48;
        int recW = std::max(10, cropW * recH / cropH);
        recW = std::min(recW, 600);  // cap width
        // Round to multiple of 4 for performance
        recW = ((recW + 3) / 4) * 4;

        auto recResized = resizeBilinear(cropped.data(), cropW, cropH, recW, recH, 3);

        // Fill recognition tensor
        auto recInput = g_rec_predictor->GetInput(0);
        fillRecTensor(recInput.get(), recResized.data(), recW, recH);

        // Run recognition
        try {
            g_rec_predictor->Run();
        } catch (...) {
            LOGE("Recognition inference failed for box %d", i);
            continue;
        }

        // Get recognition output
        auto recOutput = g_rec_predictor->GetOutput(0);
        auto recShape = recOutput->shape();
        const float* recData = recOutput->data<float>();

        int seqLen = recShape[1];
        int numClasses = recShape[2];

        // CTC decode
        std::string text = ctcDecode(recData, seqLen, numClasses);
        LOGI("Box %d: '%s' (score=%.2f)", i, text.c_str(), box.score);

        // Create float array: [confidence, x1, y1, x2, y2, x3, y3, x4, y4]
        jfloatArray jbox = env->NewFloatArray(9);
        float boxData[9] = {
            box.score,
            (float)box.pts[0], (float)box.pts[1],
            (float)box.pts[2], (float)box.pts[3],
            (float)box.pts[4], (float)box.pts[5],
            (float)box.pts[6], (float)box.pts[7]
        };
        env->SetFloatArrayRegion(jbox, 0, 9, boxData);
        env->SetObjectArrayElement(jresult, i, jbox);
        env->DeleteLocalRef(jbox);
    }

    env->DeleteLocalRef(floatArrayClass);
    return jresult;
}

JNIEXPORT void JNICALL
Java_com_omnidocs_app_ocr_PaddleNative_destroy(
    JNIEnv *env,
    jobject thiz
) {
    LOGI("PaddleOCR destroy called");
    g_det_predictor.reset();
    g_rec_predictor.reset();
    g_labels.clear();
    g_initialized = false;
}

} // extern "C"
