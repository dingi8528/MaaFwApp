#include "bridge_capture.h"

#include "bridge_frame_buffer.h"
#include "bridge_preview.h"

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <cstdint>
#include <mutex>

struct NativeCapturer {
    AImageReader *reader = nullptr;
    ANativeWindow *window = nullptr;
    AImageReader_ImageListener listener{};
    int width = 0;
    int height = 0;
    uintptr_t generation = 0;
};

static NativeCapturer *g_capturer = nullptr;
// Setup/release can arrive from a display listener and a binder thread concurrently.
static std::mutex g_captureLifecycleMutex;
// Covers acquire -> processing/preview handoff -> local AImage_delete, not reader deletion.
static std::mutex g_captureCallbackMutex;
static uintptr_t g_nextCaptureGeneration = 0;

static void onImageAvailable(void *context, AImageReader *reader) {
    std::lock_guard<std::mutex> callbackLock(g_captureCallbackMutex);
    // NDK may already have queued this callback when the listener is unregistered. The
    // context is an opaque generation number, never a dereferenced/freed capturer pointer.
    if (!g_capturer || g_capturer->reader != reader ||
        g_capturer->generation != reinterpret_cast<uintptr_t>(context)) {
        return;
    }

    AImage *image = nullptr;
    if (AImageReader_acquireLatestImage(reader, &image) != AMEDIA_OK || !image) {
        return;
    }

    AHardwareBuffer *hb = nullptr;
    if (AImage_getHardwareBuffer(image, &hb) == AMEDIA_OK && hb) {
        WriteHardwareBufferToFrame(hb);
    }

    bool handedOver = false;
    if (IsPreviewEnabled()) {
        handedOver = DispatchPreview(image);
    }

    if (!handedOver) {
        AImage_delete(image);
    }
}

// g_captureLifecycleMutex must be held. Never hold the callback mutex across reader deletion:
// AImageReader_delete may join the NDK callback looper, whose queued callbacks need that mutex.
static void ReleaseNativeCapturerLocked() {
    NativeCapturer *retiring;
    {
        std::lock_guard<std::mutex> callbackLock(g_captureCallbackMutex);
        // Taking the lock first waits for every acquired callback-local image to be released
        // or handed to the preview queue. Later queued callbacks immediately return.
        retiring = g_capturer;
        g_capturer = nullptr;
    }
    if (retiring && retiring->reader) {
        AImageReader_setImageListener(retiring->reader, nullptr);
    }

    // A preview may have already popped an image out of the queue. Wait for that image too,
    // otherwise its later AImage_delete dereferences a closed parent reader (Android 10 abort).
    DrainPreviewQueue();
    if (retiring) {
        if (retiring->reader) AImageReader_delete(retiring->reader);
        delete retiring;
        LOGI("NativeCapturer released after callbacks and preview images drained");
    }
    BlankPreview(GetFrameCount());
    ReleaseFrameBuffers();
}

jobject SetupNativeCapturer(JNIEnv *env, int width, int height) {
    std::lock_guard<std::mutex> lifecycleLock(g_captureLifecycleMutex);
    ReleaseNativeCapturerLocked();
    InitFrameBuffers(width, height);

    auto *capturer = new NativeCapturer();
    capturer->width = width;
    capturer->height = height;

    media_status_t status = AImageReader_newWithUsage(
            width, height, AIMAGE_FORMAT_RGBA_8888,
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, 5,
            &capturer->reader);
    if (status != AMEDIA_OK) {
        LOGE("AImageReader_newWithUsage failed: %d", status);
        delete capturer;
        ReleaseFrameBuffers();
        return nullptr;
    }

    status = AImageReader_getWindow(capturer->reader, &capturer->window);
    if (status != AMEDIA_OK || !capturer->window) {
        LOGE("SetupNativeCapturer: AImageReader_getWindow failed: status=%d, window=%p",
             status, capturer->window);
        AImageReader_delete(capturer->reader);
        delete capturer;
        ReleaseFrameBuffers();
        return nullptr;
    }

    {
        std::lock_guard<std::mutex> callbackLock(g_captureCallbackMutex);
        capturer->generation = ++g_nextCaptureGeneration;
        if (capturer->generation == 0) capturer->generation = ++g_nextCaptureGeneration;
        capturer->listener.context = reinterpret_cast<void *>(capturer->generation);
        capturer->listener.onImageAvailable = onImageAvailable;
        g_capturer = capturer;
    }
    status = AImageReader_setImageListener(capturer->reader, &capturer->listener);
    if (status != AMEDIA_OK) {
        LOGE("SetupNativeCapturer: AImageReader_setImageListener failed: %d", status);
        ReleaseNativeCapturerLocked();
        return nullptr;
    }
    return ANativeWindow_toSurface(env, capturer->window);
}

void ReleaseNativeCapturer() {
    std::lock_guard<std::mutex> lifecycleLock(g_captureLifecycleMutex);
    ReleaseNativeCapturerLocked();
}
