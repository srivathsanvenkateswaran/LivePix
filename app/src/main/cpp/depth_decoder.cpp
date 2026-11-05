// JNI bridge: decode one HEVC still (Annex-B) with libde265 and return its luma plane.
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <libde265/de265.h>

#define LOG_TAG "LivePixNative"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jobject JNICALL
Java_com_srivathsan_livepix_media_NativeHevc_decodeLuma(JNIEnv* env, jclass, jbyteArray annexB) {
    jsize len = env->GetArrayLength(annexB);
    jbyte* data = env->GetByteArrayElements(annexB, nullptr);

    de265_decoder_context* ctx = de265_new_decoder();
    de265_error err = de265_push_data(ctx, data, len, 0, nullptr);
    env->ReleaseByteArrayElements(annexB, data, JNI_ABORT);
    if (err != DE265_OK) {
        LOGW("push_data: %s", de265_get_error_text(err));
        de265_free_decoder(ctx);
        return nullptr;
    }
    de265_flush_data(ctx);

    const de265_image* img = nullptr;
    int more = 1;
    while (more && img == nullptr) {
        err = de265_decode(ctx, &more);
        if (err != DE265_OK && err != DE265_ERROR_WAITING_FOR_INPUT_DATA) {
            LOGW("decode: %s", de265_get_error_text(err));
            break;
        }
        img = de265_get_next_picture(ctx);
    }

    jobject result = nullptr;
    if (img != nullptr) {
        int w = de265_get_image_width(img, 0);
        int h = de265_get_image_height(img, 0);
        int stride = 0;
        const uint8_t* plane = de265_get_image_plane(img, 0, &stride);
        int bpp = de265_get_bits_per_pixel(img, 0);
        jbyteArray pixels = env->NewByteArray(w * h);
        jbyte* out = env->GetByteArrayElements(pixels, nullptr);
        if (bpp <= 8) {
            for (int y = 0; y < h; y++) memcpy(out + y * w, plane + y * stride, w);
        } else {
            // Higher bit depths are stored as 16-bit little-endian samples.
            int shift = bpp - 8;
            for (int y = 0; y < h; y++) {
                const uint16_t* row = reinterpret_cast<const uint16_t*>(plane + y * stride);
                for (int x = 0; x < w; x++) out[y * w + x] = static_cast<jbyte>(row[x] >> shift);
            }
        }
        env->ReleaseByteArrayElements(pixels, out, 0);
        jclass cls = env->FindClass("com/srivathsan/livepix/media/GrayImage");
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(II[B)V");
        result = env->NewObject(cls, ctor, w, h, pixels);
    }
    de265_free_decoder(ctx);
    return result;
}

// Decodes one colour HEVC still to ARGB_8888. matrix: 1 = BT.709, 9 = BT.2020, otherwise BT.601.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_srivathsan_livepix_media_NativeHevc_decodeArgb(JNIEnv* env, jclass, jbyteArray annexB, jint matrix, jboolean fullRange, jintArray sizeOut) {
    jsize len = env->GetArrayLength(annexB);
    jbyte* data = env->GetByteArrayElements(annexB, nullptr);
    de265_decoder_context* ctx = de265_new_decoder();
    de265_error err = de265_push_data(ctx, data, len, 0, nullptr);
    env->ReleaseByteArrayElements(annexB, data, JNI_ABORT);
    if (err != DE265_OK) { de265_free_decoder(ctx); return nullptr; }
    de265_flush_data(ctx);
    const de265_image* img = nullptr;
    int more = 1;
    while (more && img == nullptr) {
        err = de265_decode(ctx, &more);
        if (err != DE265_OK && err != DE265_ERROR_WAITING_FOR_INPUT_DATA) break;
        img = de265_get_next_picture(ctx);
    }
    if (img == nullptr) { de265_free_decoder(ctx); return nullptr; }

    const int w = de265_get_image_width(img, 0);
    const int h = de265_get_image_height(img, 0);
    const de265_chroma chroma = de265_get_chroma_format(img);
    const int bits = de265_get_bits_per_pixel(img, 0);
    int ys = 0, us = 0, vs = 0;
    const uint8_t* yp = de265_get_image_plane(img, 0, &ys);
    const uint8_t* up = chroma == de265_chroma_mono ? nullptr : de265_get_image_plane(img, 1, &us);
    const uint8_t* vp = chroma == de265_chroma_mono ? nullptr : de265_get_image_plane(img, 2, &vs);
    const int sx = chroma == de265_chroma_444 ? 0 : 1;
    const int sy = chroma == de265_chroma_420 ? 1 : 0;
    const int shift = bits > 8 ? bits - 8 : 0;

    float kr = 0.299f, kb = 0.114f;
    if (matrix == 1) { kr = 0.2126f; kb = 0.0722f; }
    else if (matrix == 9) { kr = 0.2627f; kb = 0.0593f; }
    const float kg = 1.f - kr - kb;
    const float yScale = fullRange ? 1.f : 255.f / 219.f;
    const float cScale = fullRange ? 1.f : 255.f / 224.f;
    const float yOff = fullRange ? 0.f : 16.f;

    auto sample = [&](const uint8_t* plane, int stride, int x, int y) -> int {
        if (bits <= 8) return plane[y * stride + x];
        return reinterpret_cast<const uint16_t*>(plane + y * stride)[x] >> shift;
    };

    jintArray out = env->NewIntArray(w * h);
    jint* px = env->GetIntArrayElements(out, nullptr);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            float Y = (sample(yp, ys, x, y) - yOff) * yScale;
            float Cb = 0.f, Cr = 0.f;
            if (up != nullptr) {
                Cb = (sample(up, us, x >> sx, y >> sy) - 128.f) * cScale;
                Cr = (sample(vp, vs, x >> sx, y >> sy) - 128.f) * cScale;
            }
            float r = Y + 2.f * (1.f - kr) * Cr;
            float b = Y + 2.f * (1.f - kb) * Cb;
            float g = (Y - kr * r - kb * b) / kg;
            int ri = r < 0 ? 0 : r > 255 ? 255 : (int) (r + 0.5f);
            int gi = g < 0 ? 0 : g > 255 ? 255 : (int) (g + 0.5f);
            int bi = b < 0 ? 0 : b > 255 ? 255 : (int) (b + 0.5f);
            px[y * w + x] = (jint) (0xFF000000u | (ri << 16) | (gi << 8) | bi);
        }
    }
    env->ReleaseIntArrayElements(out, px, 0);
    jint size[2] = { w, h };
    env->SetIntArrayRegion(sizeOut, 0, 2, size);
    de265_free_decoder(ctx);
    return out;
}
