# PaddleOCR Android Integration Guide

## Overview

This guide explains how to integrate PaddleOCR into the OmniDocs Android app for improved OCR accuracy.

## Requirements

1. **ncnn library** - Neural network inference framework
   - Download: https://github.com/Tencent/ncnn/releases
   - Get: `ncnn-YYYYMMDD-android-vulkan.zip`

2. **OpenCV mobile** - Computer vision library
   - Download: https://github.com/nihui/opencv-mobile
   - Get: `opencv-mobile-XYZ-android.zip`

3. **PaddleOCR models** - OCR detection and recognition models
   - Source: https://github.com/PaddlePaddle/PaddleOCR
   - Need: Detection model, Recognition model, Angle classifier

## Step 1: Download Dependencies

```bash
# Download ncnn
wget https://github.com/Tencent/ncnn/releases/download/20240410/ncnn-20240410-android-vulkan.zip

# Download OpenCV mobile
wget https://github.com/nihui/opencv-mobile/releases/download/2.6.0/opencv-mobile-2.6.0-android.zip
```

## Step 2: Extract to Project

```
app/src/main/jni/
├── ncnn-android-vulkan/
│   ├── arm64-v8a/
│   ├── armeabi-v7a/
│   └── ...
├── opencv-mobile/
│   ├── arm64-v8a/
│   ├── armeabi-v7a/
│   └── ...
└── paddleocr/
    ├── det/
    │   └── model.ncnn
    ├── rec/
    │   └── model.ncnn
    └── cls/
        └── model.ncnn
```

## Step 3: Convert PaddleOCR Models to NCNN

```bash
# Install paddle2onnx
pip install paddle2onnx

# Convert detection model
paddle2onnx --model_dir ./det \
    --model_filename inference.pdmodel \
    --params_filename inference.pdiparams \
    --save_file ./det.onnx \
    --opset_version 11

# Convert to NCNN
./ncnn2ncnn det.onnx det.param det.bin

# Repeat for recognition and angle models
```

## Step 4: C++ OCR Pipeline

```cpp
// paddle_ocr_jni.cpp
#include <jni.h>
#include <string>
#include <vector>
#include "ncnn/net.h"
#include "opencv2/opencv.hpp"

static ncnn::Net det_net, rec_net, cls_net;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_omnidocs_ocr_PaddleOcrService_nativeInit(
    JNIEnv *env, jobject thiz,
    jstring detParam, jstring detBin,
    jstring recParam, jstring recBin,
    jstring clsParam, jstring clsBin
) {
    const char *det_p = env->GetStringUTFChars(detParam, nullptr);
    const char *det_b = env->GetStringUTFChars(detBin, nullptr);
    const char *rec_p = env->GetStringUTFChars(recParam, nullptr);
    const char *rec_b = env->GetStringUTFChars(recBin, nullptr);
    const char *cls_p = env->GetStringUTFChars(clsParam, nullptr);
    const char *cls_b = env->GetStringUTFChars(clsBin, nullptr);

    det_net.load_param(det_p);
    det_net.load_model(det_b);
    rec_net.load_param(rec_p);
    rec_net.load_model(rec_b);
    cls_net.load_param(cls_p);
    cls_net.load_model(cls_b);

    env->ReleaseStringUTFChars(detParam, det_p);
    env->ReleaseStringUTFChars(detBin, det_b);
    env->ReleaseStringUTFChars(recParam, rec_p);
    env->ReleaseStringUTFChars(recBin, rec_b);
    env->ReleaseStringUTFChars(clsParam, cls_p);
    env->ReleaseStringUTFChars(clsBin, cls_b);

    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_omnidocs_ocr_PaddleOcrService_nativeRecognize(
    JNIEnv *env, jobject thiz,
    jstring imagePath
) {
    const char *path = env->GetStringUTFChars(imagePath, nullptr);

    cv::Mat img = cv::imread(path);
    if (img.empty()) {
        env->ReleaseStringUTFChars(imagePath, path);
        return env->NewStringUTF("");
    }

    // Text detection
    std::vector<std::vector<cv::Point>> boxes = detectText(img);

    // Text recognition for each box
    std::string result;
    for (auto &box : boxes) {
        cv::Mat crop = cropImage(img, box);
        std::string text = recognizeText(crop);
        result += text + "\n";
    }

    env->ReleaseStringUTFChars(imagePath, path);
    return env->NewStringUTF(result.c_str());
}

std::vector<std::vector<cv::Point>> detectText(cv::Mat &img) {
    // Preprocess image for detection
    cv::Mat blob = cv::dnn::blobFromImage(img, 1.0/255.0, cv::Size(960, 960));
    det_net.input("input", blob);

    // Run detection
    std::vector<ncnn::Mat> out;
    det_net.forward("output", out);

    // Post-process to get bounding boxes
    // ... (implementation depends on model output format)

    return boxes;
}

cv::Mat cropImage(cv::Mat &img, std::vector<cv::Point> &box) {
    cv::Rect rect = cv::boundingRect(box);
    return img(rect).clone();
}

std::string recognizeText(cv::Mat &crop) {
    // Preprocess for recognition
    cv::Mat blob = cv::dnn::blobFromImage(crop, 1.0/255.0, cv::Size(320, 32));
    rec_net.input("input", blob);

    // Run recognition
    std::vector<ncnn::Mat> out;
    rec_net.forward("output", out);

    // Decode output to text
    // ... (implementation depends on model output format)

    return text;
}

} // extern "C"
```

## Step 5: Kotlin Integration

```kotlin
// PaddleOcrService.kt
@Singleton
class PaddleOcrService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    init {
        System.loadLibrary("paddle_ocr")
    }

    private external fun nativeInit(
        detParam: String, detBin: String,
        recParam: String, recBin: String,
        clsParam: String, clsBin: String
    ): Boolean

    private external fun nativeRecognize(imagePath: String): String

    fun initialize(): Boolean {
        val detDir = File(context.filesDir, "paddleocr/det")
        val recDir = File(context.filesDir, "paddleocr/rec")
        val clsDir = File(context.filesDir, "paddleocr/cls")

        return nativeInit(
            "${detDir}/model.param", "${detDir}/model.bin",
            "${recDir}/model.param", "${recDir}/model.bin",
            "${clsDir}/model.param", "${clsDir}/model.bin"
        )
    }

    fun recognize(imagePath: String): String {
        return nativeRecognize(imagePath)
    }
}
```

## Step 6: Update CMakeLists.txt

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("paddle_ocr")

# ncnn
set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

# OpenCV
set(OpenCV_DIR ${CMAKE_SOURCE_DIR}/opencv-mobile/${ANDROID_ABI}/lib/cmake/opencv4)
find_package(OpenCV REQUIRED)

add_library(paddle_ocr SHARED paddle_ocr_jni.cpp)
target_include_directories(paddle_ocr PRIVATE ${OpenCV_INCLUDE_DIRS})
target_link_libraries(paddle_ocr ncnn ${OpenCV_LIBS} log)
```

## Step 7: Download Models

Place converted NCNN models in:
```
app/src/main/assets/paddleocr/
├── det/
│   ├── model.param
│   └── model.bin
├── rec/
│   ├── model.param
│   └── model.bin
└── cls/
    ├── model.param
    └── model.bin
```

## Performance Notes

- **Detection**: ~50ms on Snapdragon 865
- **Recognition**: ~20ms per text line
- **Total**: ~100-200ms for typical document

## Troubleshooting

1. **Build fails**: Ensure ncnn and OpenCV paths are correct in CMakeLists.txt
2. **Model not found**: Check that model files are in assets directory
3. **Low accuracy**: Adjust preprocessing (threshold, contrast)
4. **Slow inference**: Reduce input image size or use GPU acceleration

## References

- PaddleOCR: https://github.com/PaddlePaddle/PaddleOCR
- ncnn: https://github.com/Tencent/ncnn
- OpenCV mobile: https://github.com/nihui/opencv-mobile
- ncnn_paddleocr demo: https://github.com/FeiGeChuanShu/ncnn_paddleocr
