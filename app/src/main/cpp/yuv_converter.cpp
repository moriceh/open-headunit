#include <jni.h>
#include <cstdint>
#include <cstring>
#include <atomic>
#include <android/log.h>

#define MS9120_LOG_TAG "YuvConverterNative"
#define MS9120_LOGI(...) __android_log_print(ANDROID_LOG_INFO, MS9120_LOG_TAG, __VA_ARGS__)

// The bulk USB transfer is handled on the framework side by
// UsbDeviceConnection.bulkTransfer() (Kotlin), exactly as in the official app (useThread=0 path,
// 100% Java). This library only provides the YUV420 -> wire-format conversion.
//
// Three wire formats are supported (low nibble of the color byte, cf. the official app's
// Util.COLORSPACE: RGB565=0, RGB888=1, YUV422=2):
//   format 1 (RGB888) : 3 bytes/pixel, order B,G,R on the wire (the chip MS91xx reads the wire as
//                       BGR, not RGB: verified empirically).
//   format 2 (YUV422) : 2 bytes/pixel, UYVY interleaving.
//   format 0 (RGB565) : 2 bytes/pixel, standard 5-6-5 packing (little-endian).
//
// The wire bytes are written into the output buffer; the chip reads them verbatim on the Bulk OUT
// endpoint.

extern "C" JNIEXPORT void JNICALL
Java_com_andrerinas_openheadunit_aap_ms9120_YuvConverter_nativeConvertYuv420To720p(
    JNIEnv *env,
    jobject /* thiz */,
    jobject yBuffer,
    jint yRowStride,
    jint yPixelStride,
    jobject uBuffer,
    jobject vBuffer,
    jint uvRowStride,
    jint uvPixelStride,
    jint imgWidth,
    jint imgHeight,
    jint dstWidth,
    jint dstHeight,
    jint format,          // 1 = BGR888 (3 o/pix), 2 = YUV422 (2 o/pix), 0 = RGB565 (2 o/pix)
    jboolean stretch,     // true = fill the whole destination (aspect ratio ignored)
    jbyteArray outArray)
{
    auto *yPlane = static_cast<const uint8_t *>(env->GetDirectBufferAddress(yBuffer));
    auto *uPlane = static_cast<const uint8_t *>(env->GetDirectBufferAddress(uBuffer));
    auto *vPlane = static_cast<const uint8_t *>(env->GetDirectBufferAddress(vBuffer));

    if (!yPlane || !uPlane || !vPlane || imgWidth <= 0 || imgHeight <= 0) {
        static std::atomic<bool> logged{false};
        if (!logged.exchange(true)) {
            MS9120_LOGI("native: non-direct or empty plane -> no C++ conversion possible");
        }
        return;
    }

    {
        static std::atomic<bool> logged{false};
        if (!logged.exchange(true)) {
            MS9120_LOGI("Native C++ library active (YUV420 direct): src=%dx%d dst=%dx%d format=%d",
                        imgWidth, imgHeight, dstWidth, dstHeight, format);
        }
    }

    auto *dst = static_cast<uint8_t *>(env->GetPrimitiveArrayCritical(outArray, nullptr));
    if (!dst) {
        return;
    }

    const int bytesPerPixel = (format == 1) ? 3 : 2;   // RGB888=3, YUV422/RGB565=2

    // 1. Black background (UYVY black is U=128, Y0=16, V=128, Y1=16; zeros produce bright green)
    if (format == 2) {
        size_t totalBytes = static_cast<size_t>(dstWidth) * dstHeight * 2;
        for (size_t i = 0; i + 3 < totalBytes; i += 4) {
            dst[i]     = 128; // U
            dst[i + 1] = 16;  // Y0
            dst[i + 2] = 128; // V
            dst[i + 3] = 16;  // Y1
        }
    } else {
        memset(dst, 0, static_cast<size_t>(dstWidth) * dstHeight * bytesPerPixel);
    }

    // 2. Destination box.
    //    stretch=true : fill the WHOLE destination (aspect ratio ignored, image stretched).
    //    stretch=false: scale-to-fit (aspect ratio preserved, black bars).
    int64_t fitW = 0, fitH = 0;
    if (stretch) {
        fitW = dstWidth;
        fitH = dstHeight;
    } else if ((int64_t) dstWidth * imgHeight <= (int64_t) dstHeight * imgWidth) {
        fitW = dstWidth;
        fitH = (int64_t) dstWidth * imgHeight / imgWidth;
    } else {
        fitH = dstHeight;
        fitW = (int64_t) dstHeight * imgWidth / imgHeight;
    }
    fitW &= ~1;
    fitH &= ~1;
    if (fitW < 2) fitW = 2;
    if (fitH < 2) fitH = 2;
    int offX = (dstWidth - (int) fitW) / 2;
    int offY = (dstHeight - (int) fitH) / 2;

    // Sample a source pixel (nearest) -> R,G,B (BT.601 limited range)
    auto sample = [&](int y, int x, const uint8_t *yRow,
                      const uint8_t *uRow, const uint8_t *vRow,
                      int &R, int &G, int &B) {
        int srcX = (x - offX) * imgWidth / (int) fitW;
        if (srcX > imgWidth - 1) srcX = imgWidth - 1;
        int Yc = yRow[(int64_t) srcX * yPixelStride] - 16;
        int Uc = uRow[(int64_t) (srcX >> 1) * uvPixelStride] - 128;
        int Vc = vRow[(int64_t) (srcX >> 1) * uvPixelStride] - 128;
        // BT.601 limited-range coefficients, fixed point /4096 (>>12):
        //   R = 1.1644*Yc + 1.5961*Vc
        //   G = 1.1644*Yc - 0.3918*Uc - 0.8135*Vc
        //   B = 1.1644*Yc + 2.0175*Uc
        R = (4771 * Yc + 6538 * Vc + 2048) >> 12;
        G = (4771 * Yc - 1605 * Uc - 3332 * Vc + 2048) >> 12;
        B = (4771 * Yc + 8264 * Uc + 2048) >> 12;
        if (R < 0) R = 0; else if (R > 255) R = 255;
        if (G < 0) G = 0; else if (G > 255) G = 255;
        if (B < 0) B = 0; else if (B > 255) B = 255;
    };

    if (format == 2) {
        // YUV422 (UYVY): 2 bytes/pixel. Pairs of pixels -> [U, Y0, V, Y1].
        // The chip MS91xx reads the wire as UYVY (cf. the official app's RGB32ToUYVY).
        // The decoder planes are already YUV: we copy Y/U/V without any RGB conversion.
        for (int y = offY; y < offY + (int) fitH; y++) {
            int srcY = (y - offY) * imgHeight / (int) fitH;
            if (srcY > imgHeight - 1) srcY = imgHeight - 1;
            const uint8_t *yRow = yPlane + (int64_t) srcY * yRowStride;
            const uint8_t *uRow = uPlane + (int64_t) (srcY >> 1) * uvRowStride;
            const uint8_t *vRow = vPlane + (int64_t) (srcY >> 1) * uvRowStride;

            int x = offX;
            while (x + 1 < offX + (int) fitW) {
                int srcXA = (x - offX) * imgWidth / (int) fitW;
                int srcXB = (x + 1 - offX) * imgWidth / (int) fitW;
                if (srcXA > imgWidth - 1) srcXA = imgWidth - 1;
                if (srcXB > imgWidth - 1) srcXB = imgWidth - 1;

                int o = (y * dstWidth + x) * 2;
                dst[o]     = uRow[(int64_t) (srcXA >> 1) * uvPixelStride]; // U
                dst[o + 1] = yRow[(int64_t) srcXA * yPixelStride];         // Y0
                dst[o + 2] = vRow[(int64_t) (srcXA >> 1) * uvPixelStride]; // V
                dst[o + 3] = yRow[(int64_t) srcXB * yPixelStride];         // Y1
                x += 2;
            }
        }
        env->ReleasePrimitiveArrayCritical(outArray, dst, 0);
        return;
    }

    // 3. RGB888 (BGR on the wire) or RGB565
    for (int y = offY; y < offY + (int) fitH; y++) {
        int srcY = (y - offY) * imgHeight / (int) fitH;
        if (srcY > imgHeight - 1) srcY = imgHeight - 1;
        const uint8_t *yRow = yPlane + (int64_t) srcY * yRowStride;
        const uint8_t *uRow = uPlane + (int64_t) (srcY >> 1) * uvRowStride;
        const uint8_t *vRow = vPlane + (int64_t) (srcY >> 1) * uvRowStride;

        for (int x = offX; x < offX + (int) fitW; x++) {
            int R, G, B;
            sample(y, x, yRow, uRow, vRow, R, G, B);

            int o = (y * dstWidth + x) * bytesPerPixel;
            if (format == 1) {
                // The chip reads the wire as BGR (not RGB).
                dst[o]     = (uint8_t) B;
                dst[o + 1] = (uint8_t) G;
                dst[o + 2] = (uint8_t) R;
            } else {
                // RGB565: 5-6-5 packed, little-endian (low byte first).
                uint16_t v565 = (uint16_t) (((R >> 3) << 11) | ((G >> 2) << 5) | (B >> 3));
                dst[o]     = (uint8_t) (v565 & 0xFF);
                dst[o + 1] = (uint8_t) (v565 >> 8);
            }
        }
    }

    env->ReleasePrimitiveArrayCritical(outArray, dst, 0);
}
