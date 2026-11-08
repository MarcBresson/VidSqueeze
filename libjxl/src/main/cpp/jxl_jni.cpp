// JNI bridge: encodes raw pixels to a JPEG XL codestream through libjxl's C API.
// The Kotlin side (app.squeeze.jxl.Jxl) owns the parameter surface; this file is the only
// consumer of libjxl headers.
#include <jni.h>
#include <jxl/color_encoding.h>
#include <jxl/encode.h>
#include <jxl/types.h>

#include <vector>

namespace {

// Drains JxlEncoderProcessOutput into a growing byte vector.
bool drain(JxlEncoder* enc, std::vector<uint8_t>& out) {
    std::vector<uint8_t> chunk(65536);
    JxlEncoderStatus st = JXL_ENC_SUCCESS;
    do {
        uint8_t* next = chunk.data();
        size_t avail = chunk.size();
        st = JxlEncoderProcessOutput(enc, &next, &avail);
        if (st == JXL_ENC_ERROR) return false;
        out.insert(out.end(), chunk.data(), next);
    } while (st != JXL_ENC_SUCCESS);
    return true;
}

} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_squeeze_jxl_Jxl_encodeJxl(JNIEnv* env, jclass, jint width, jint height, jboolean hasAlpha,
                                   jint bitDepth, jint effort, jdouble distance, jboolean modular,
                                   jboolean xyb, jbyteArray pixels, jbyteArray) {
    JxlEncoder* enc = JxlEncoderCreate(nullptr);

    // InitBasicInfo, not memset(0): this snapshot rejects orientation == 0
    // ("Invalid value for orientation field") and num_extra_channels == 0 when
    // alpha_bits is set — both API_USAGE (0x81) failures.
    JxlBasicInfo info;
    JxlEncoderInitBasicInfo(&info);
    info.xsize = width;
    info.ysize = height;
    info.num_color_channels = 3;
    info.num_extra_channels = hasAlpha ? 1 : 0;
    // bitDepth: 8 and 16 are integer samples; 32 means float16 pixels.
    if (bitDepth == 32) {
        info.bits_per_sample = 16;
        info.exponent_bits_per_sample = 5;
        if (hasAlpha) {
            info.alpha_bits = 16;
            info.alpha_exponent_bits = 5;
        }
    } else {
        info.bits_per_sample = bitDepth >= 16 ? 16 : 8;
        info.exponent_bits_per_sample = 0;
        if (hasAlpha) {
            info.alpha_bits = info.bits_per_sample;
            info.alpha_exponent_bits = 0;
        }
    }
    // Lossless keeps the original colour profile instead of going through XYB.
    info.uses_original_profile = distance <= 0.0 && !xyb;

    if (JxlEncoderSetBasicInfo(enc, &info) != JXL_ENC_SUCCESS) {
        JxlEncoderDestroy(enc);
        return nullptr;
    }

    JxlColorEncoding color;
    JxlColorEncodingSetToSRGB(&color, /*is_gray=*/JXL_FALSE);
    if (JxlEncoderSetColorEncoding(enc, &color) != JXL_ENC_SUCCESS) {
        JxlEncoderDestroy(enc);
        return nullptr;
    }

    JxlEncoderFrameSettings* frame = JxlEncoderFrameSettingsCreate(enc, nullptr);
    JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_EFFORT, effort);
    JxlEncoderSetFrameDistance(frame, static_cast<float>(distance));
    if (modular) {
        JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_MODULAR, 1);
    }

    JxlPixelFormat fmt;
    fmt.num_channels = hasAlpha ? 4 : 3;
    fmt.endianness = JXL_NATIVE_ENDIAN;
    fmt.align = 0;
    if (bitDepth == 16) fmt.data_type = JXL_TYPE_UINT16;
    else if (bitDepth == 32) fmt.data_type = JXL_TYPE_FLOAT16;
    else fmt.data_type = JXL_TYPE_UINT8;

    const jsize pixelSize = env->GetArrayLength(pixels);
    std::vector<uint8_t> bytes(static_cast<size_t>(pixelSize));
    env->GetByteArrayRegion(pixels, 0, pixelSize, reinterpret_cast<jbyte*>(bytes.data()));

    if (JxlEncoderAddImageFrame(frame, &fmt, bytes.data(), bytes.size()) != JXL_ENC_SUCCESS) {
        JxlEncoderDestroy(enc);
        return nullptr;
    }
    // Single frame: close the stream, then drain the codestream.
    JxlEncoderCloseFrames(enc);
    JxlEncoderCloseInput(enc);

    std::vector<uint8_t> out;
    if (!drain(enc, out)) {
        JxlEncoderDestroy(enc);
        return nullptr;
    }
    JxlEncoderDestroy(enc);

    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()),
                            reinterpret_cast<const jbyte*>(out.data()));
    return result;
}
