#include <jni.h>

#include <cerrno>
#include <climits>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include <sys/types.h>
#include <unistd.h>

#include <wavpack.h>

namespace {

constexpr int kMaxTagCount = 512;
constexpr int kMaxTagNameBytes = 4 * 1024;
constexpr int kMaxTextTagBytes = 1024 * 1024;
constexpr std::size_t kMaxTotalTextTagBytes = 2 * 1024 * 1024;
constexpr int kMaxBinaryTagBytes = 32 * 1024 * 1024;
constexpr int kMaxChannelCount = 256;

thread_local std::string last_error;

struct FdStream {
    int fd = -1;
};

struct WavPackDecoder {
    FdStream source;
    FdStream correction;
    WavpackContext* context = nullptr;
};

void set_error(std::string message) {
    last_error = std::move(message);
}

void set_errno_error(const char* operation) {
    set_error(std::string(operation) + ": " + std::strerror(errno));
}

int32_t stream_read(void* id, void* data, int32_t byte_count) {
    auto* stream = static_cast<FdStream*>(id);
    if (stream == nullptr || stream->fd < 0 || data == nullptr || byte_count <= 0) return 0;

    int32_t total = 0;
    while (total < byte_count) {
        const ssize_t count = read(
            stream->fd,
            static_cast<unsigned char*>(data) + total,
            static_cast<size_t>(byte_count - total)
        );
        if (count > 0) {
            total += static_cast<int32_t>(count);
            continue;
        }
        if (count < 0 && errno == EINTR) continue;
        break;
    }
    return total;
}

int32_t stream_write(void*, void*, int32_t) {
    return 0;
}

int64_t stream_position(void* id) {
    auto* stream = static_cast<FdStream*>(id);
    if (stream == nullptr || stream->fd < 0) return -1;
    return static_cast<int64_t>(lseek(stream->fd, 0, SEEK_CUR));
}

int stream_seek_absolute(void* id, int64_t position) {
    auto* stream = static_cast<FdStream*>(id);
    if (stream == nullptr || stream->fd < 0 || position < 0) return -1;
    return lseek(stream->fd, static_cast<off_t>(position), SEEK_SET) >= 0 ? 0 : -1;
}

int stream_seek_relative(void* id, int64_t delta, int mode) {
    auto* stream = static_cast<FdStream*>(id);
    if (stream == nullptr || stream->fd < 0) return -1;
    return lseek(stream->fd, static_cast<off_t>(delta), mode) >= 0 ? 0 : -1;
}

int stream_push_back(void* id, int value) {
    auto* stream = static_cast<FdStream*>(id);
    if (stream == nullptr || stream->fd < 0) return EOF;
    return lseek(stream->fd, -1, SEEK_CUR) >= 0 ? value : EOF;
}

int64_t stream_length(void* id) {
    auto* stream = static_cast<FdStream*>(id);
    if (stream == nullptr || stream->fd < 0) return 0;
    const off_t current = lseek(stream->fd, 0, SEEK_CUR);
    if (current < 0) return 0;
    const off_t end = lseek(stream->fd, 0, SEEK_END);
    if (end < 0) return 0;
    if (lseek(stream->fd, current, SEEK_SET) < 0) return 0;
    return static_cast<int64_t>(end);
}

int stream_can_seek(void* id) {
    auto* stream = static_cast<FdStream*>(id);
    return stream != nullptr && stream->fd >= 0 && lseek(stream->fd, 0, SEEK_CUR) >= 0;
}

int stream_truncate(void*) {
    return -1;
}

int stream_close(void* id) {
    auto* stream = static_cast<FdStream*>(id);
    if (stream == nullptr || stream->fd < 0) return 0;
    const int result = close(stream->fd);
    stream->fd = -1;
    return result;
}

WavpackStreamReader64 stream_reader = {
    stream_read,
    stream_write,
    stream_position,
    stream_seek_absolute,
    stream_seek_relative,
    stream_push_back,
    stream_length,
    stream_can_seek,
    stream_truncate,
    stream_close,
};

bool duplicate_seekable_fd(int input_fd, FdStream* output, const char* label) {
    if (input_fd < 0 || output == nullptr) {
        set_error(std::string(label) + " file descriptor is invalid");
        return false;
    }

    output->fd = dup(input_fd);
    if (output->fd < 0) {
        set_errno_error("Could not duplicate WavPack file descriptor");
        return false;
    }
    if (lseek(output->fd, 0, SEEK_SET) < 0) {
        set_error(std::string(label) + " file descriptor is not seekable");
        stream_close(output);
        return false;
    }
    return true;
}

WavPackDecoder* from_handle(jlong handle) {
    return reinterpret_cast<WavPackDecoder*>(static_cast<std::uintptr_t>(handle));
}

jlong to_handle(WavPackDecoder* decoder) {
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(decoder));
}

void destroy(WavPackDecoder* decoder) {
    if (decoder == nullptr) return;
    if (decoder->context != nullptr) {
        WavpackCloseFile(decoder->context);
        decoder->context = nullptr;
    }
    stream_close(&decoder->source);
    stream_close(&decoder->correction);
    delete decoder;
}

jstring new_utf8_string(JNIEnv* env, const char* data, std::size_t size) {
    if (data == nullptr || size == 0) return env->NewStringUTF("");
    if (size > static_cast<std::size_t>(INT_MAX)) return nullptr;

    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(size));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(
        bytes,
        0,
        static_cast<jsize>(size),
        reinterpret_cast<const jbyte*>(data)
    );
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(bytes);
        return nullptr;
    }

    jclass string_class = env->FindClass("java/lang/String");
    jmethodID constructor = string_class == nullptr
        ? nullptr
        : env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    jstring result = constructor == nullptr || charset == nullptr
        ? nullptr
        : static_cast<jstring>(env->NewObject(string_class, constructor, bytes, charset));

    if (charset != nullptr) env->DeleteLocalRef(charset);
    if (string_class != nullptr) env->DeleteLocalRef(string_class);
    env->DeleteLocalRef(bytes);
    return result;
}

jobjectArray new_string_array(JNIEnv* env, const std::vector<std::string>& values) {
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr || values.size() > static_cast<std::size_t>(INT_MAX)) return nullptr;

    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(values.size()),
        string_class,
        nullptr
    );
    env->DeleteLocalRef(string_class);
    if (result == nullptr) return nullptr;

    for (std::size_t index = 0; index < values.size(); ++index) {
        const std::string& value = values[index];
        jstring item = new_utf8_string(env, value.data(), value.size());
        if (item == nullptr) return result;
        env->SetObjectArrayElement(result, static_cast<jsize>(index), item);
        env->DeleteLocalRef(item);
        if (env->ExceptionCheck()) return result;
    }
    return result;
}

bool indexed_tag_name(
    WavpackContext* context,
    int index,
    bool binary,
    std::string* output
) {
    if (context == nullptr || output == nullptr || index < 0) return false;
    const int length = binary
        ? WavpackGetBinaryTagItemIndexed(context, index, nullptr, 0)
        : WavpackGetTagItemIndexed(context, index, nullptr, 0);
    if (length <= 0 || length > kMaxTagNameBytes) return false;

    std::vector<char> name(static_cast<std::size_t>(length) + 1);
    const int actual = binary
        ? WavpackGetBinaryTagItemIndexed(context, index, name.data(), length + 1)
        : WavpackGetTagItemIndexed(context, index, name.data(), length + 1);
    if (actual <= 0 || actual > length) return false;
    output->assign(name.data(), static_cast<std::size_t>(actual));
    return true;
}

std::vector<std::string> text_tag_pairs(WavpackContext* context) {
    std::vector<std::string> result;
    if (context == nullptr) return result;

    const int count = WavpackGetNumTagItems(context);
    const int bounded_count = count < 0 ? 0 : (count > kMaxTagCount ? kMaxTagCount : count);
    result.reserve(static_cast<std::size_t>(bounded_count) * 2);
    std::size_t total_bytes = 0;
    for (int index = 0; index < bounded_count; ++index) {
        std::string name;
        if (!indexed_tag_name(context, index, false, &name)) continue;

        const int value_length = WavpackGetTagItem(context, name.c_str(), nullptr, 0);
        if (value_length <= 0 || value_length > kMaxTextTagBytes) continue;
        const std::size_t requested_bytes = name.size() + static_cast<std::size_t>(value_length);
        if (requested_bytes > kMaxTotalTextTagBytes - total_bytes) continue;
        std::vector<char> value(static_cast<std::size_t>(value_length) + 1);
        const int actual = WavpackGetTagItem(
            context,
            name.c_str(),
            value.data(),
            value_length + 1
        );
        if (actual <= 0 || actual > value_length) continue;
        total_bytes += name.size() + static_cast<std::size_t>(actual);
        result.push_back(std::move(name));
        result.emplace_back(value.data(), static_cast<std::size_t>(actual));
    }
    return result;
}

std::vector<std::string> binary_tag_names(WavpackContext* context) {
    std::vector<std::string> result;
    if (context == nullptr) return result;

    const int count = WavpackGetNumBinaryTagItems(context);
    const int bounded_count = count < 0 ? 0 : (count > kMaxTagCount ? kMaxTagCount : count);
    result.reserve(static_cast<std::size_t>(bounded_count));
    for (int index = 0; index < bounded_count; ++index) {
        std::string name;
        // Preserve the library index even for an invalid/oversized name because the Java side
        // uses this position to request the corresponding binary value.
        if (!indexed_tag_name(context, index, true, &name)) name.clear();
        result.push_back(std::move(name));
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_wavpack_NativeWavPackBridge_nativeIsSeekable(
    JNIEnv*,
    jobject,
    jint input_fd
) {
    if (input_fd < 0) return JNI_FALSE;
    const int owned_fd = dup(input_fd);
    if (owned_fd < 0) return JNI_FALSE;
    const bool seekable = lseek(owned_fd, 0, SEEK_CUR) >= 0;
    close(owned_fd);
    return seekable ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_wavpack_NativeWavPackBridge_nativeOpen(
    JNIEnv*,
    jobject,
    jint source_fd,
    jint correction_fd
) {
    last_error.clear();
    std::unique_ptr<WavPackDecoder, void (*)(WavPackDecoder*)> decoder(
        new WavPackDecoder(),
        destroy
    );
    if (!duplicate_seekable_fd(source_fd, &decoder->source, "WavPack source")) return 0;

    void* correction_id = nullptr;
    int flags = OPEN_TAGS;
    if (correction_fd >= 0) {
        if (!duplicate_seekable_fd(
                correction_fd,
                &decoder->correction,
                "WavPack correction"
            )) {
            return 0;
        }
        correction_id = &decoder->correction;
        flags |= OPEN_WVC;
    }

    char error[80] = {};
    decoder->context = WavpackOpenFileInputEx64(
        &stream_reader,
        &decoder->source,
        correction_id,
        error,
        flags,
        0
    );
    if (decoder->context == nullptr) {
        set_error(error[0] == '\0' ? "The selected file is not a valid WavPack stream" : error);
        return 0;
    }

    const int channels = WavpackGetNumChannels(decoder->context);
    const int bits_per_sample = WavpackGetBitsPerSample(decoder->context);
    const uint32_t sample_rate = WavpackGetSampleRate(decoder->context);
    if (channels < 1 || channels > kMaxChannelCount || bits_per_sample < 1 ||
        bits_per_sample > 32 || sample_rate == 0) {
        set_error("WavPack stream reports an unsupported PCM format");
        return 0;
    }
    if ((WavpackGetQualifyMode(decoder->context) & QMODE_DSD_AUDIO) != 0) {
        set_error("DSD-in-WavPack is not supported by this PCM decoder");
        return 0;
    }

    WavPackDecoder* result = decoder.release();
    return to_handle(result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_wavpack_NativeWavPackBridge_nativeLastError(
    JNIEnv* env,
    jobject
) {
    return new_utf8_string(env, last_error.data(), last_error.size());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_wavpack_NativeWavPackBridge_nativeTechnicalInfo(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    auto* decoder = from_handle(handle);
    if (decoder == nullptr || decoder->context == nullptr) {
        set_error("WavPack decoder is closed");
        return nullptr;
    }

    const uint32_t channel_mask = static_cast<uint32_t>(WavpackGetChannelMask(decoder->context));
    const uint32_t layout_tag = WavpackGetChannelLayout(decoder->context, nullptr);
    const jlong values[] = {
        static_cast<jlong>(WavpackGetSampleRate(decoder->context)),
        static_cast<jlong>(WavpackGetNativeSampleRate(decoder->context)),
        static_cast<jlong>(WavpackGetBitsPerSample(decoder->context)),
        static_cast<jlong>(WavpackGetBytesPerSample(decoder->context)),
        static_cast<jlong>(WavpackGetNumChannels(decoder->context)),
        static_cast<jlong>(channel_mask),
        static_cast<jlong>(WavpackGetNumSamples64(decoder->context)),
        static_cast<jlong>(WavpackGetMode(decoder->context)),
        static_cast<jlong>(WavpackGetQualifyMode(decoder->context)),
        static_cast<jlong>(layout_tag),
        static_cast<jlong>(WavpackGetFileFormat(decoder->context)),
    };
    constexpr jsize count = sizeof(values) / sizeof(values[0]);
    jlongArray result = env->NewLongArray(count);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, count, values);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_wavpack_NativeWavPackBridge_nativeChannelIdentities(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    auto* decoder = from_handle(handle);
    if (decoder == nullptr || decoder->context == nullptr) return nullptr;
    const int channels = WavpackGetNumChannels(decoder->context);
    if (channels <= 0 || channels > kMaxChannelCount) return nullptr;

    std::vector<unsigned char> identities(static_cast<std::size_t>(channels) + 1);
    WavpackGetChannelIdentities(decoder->context, identities.data());
    jbyteArray result = env->NewByteArray(channels);
    if (result != nullptr) {
        env->SetByteArrayRegion(
            result,
            0,
            channels,
            reinterpret_cast<const jbyte*>(identities.data())
        );
    }
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_wavpack_NativeWavPackBridge_nativeChannelReordering(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    auto* decoder = from_handle(handle);
    if (decoder == nullptr || decoder->context == nullptr) return nullptr;
    const uint32_t layout_tag = WavpackGetChannelLayout(decoder->context, nullptr);
    const int channels_in_layout = static_cast<int>(layout_tag & 0xffU);
    if (channels_in_layout <= 0 || channels_in_layout > kMaxChannelCount) return nullptr;

    std::vector<unsigned char> reorder(static_cast<std::size_t>(channels_in_layout));
    WavpackGetChannelLayout(decoder->context, reorder.data());
    jbyteArray result = env->NewByteArray(channels_in_layout);
    if (result != nullptr) {
        env->SetByteArrayRegion(
            result,
            0,
            channels_in_layout,
            reinterpret_cast<const jbyte*>(reorder.data())
        );
    }
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_wavpack_NativeWavPackBridge_nativeStoredMd5(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    auto* decoder = from_handle(handle);
    if (decoder == nullptr || decoder->context == nullptr) return nullptr;
    unsigned char md5[16] = {};
    if (!WavpackGetMD5Sum(decoder->context, md5)) return nullptr;
    jbyteArray result = env->NewByteArray(16);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, 16, reinterpret_cast<const jbyte*>(md5));
    }
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_wavpack_NativeWavPackBridge_nativeTextTagPairs(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    auto* decoder = from_handle(handle);
    return new_string_array(
        env,
        decoder == nullptr ? std::vector<std::string>() : text_tag_pairs(decoder->context)
    );
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_wavpack_NativeWavPackBridge_nativeBinaryTagNames(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    auto* decoder = from_handle(handle);
    return new_string_array(
        env,
        decoder == nullptr ? std::vector<std::string>() : binary_tag_names(decoder->context)
    );
}

extern "C" JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_wavpack_NativeWavPackBridge_nativeBinaryTagSize(
    JNIEnv*,
    jobject,
    jlong handle,
    jint index
) {
    auto* decoder = from_handle(handle);
    if (decoder == nullptr || decoder->context == nullptr || index < 0) return -1;
    std::string name;
    if (!indexed_tag_name(decoder->context, index, true, &name)) return -1;
    return WavpackGetBinaryTagItem(decoder->context, name.c_str(), nullptr, 0);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_wavpack_NativeWavPackBridge_nativeReadBinaryTag(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint index,
    jint maximum_bytes
) {
    last_error.clear();
    auto* decoder = from_handle(handle);
    if (decoder == nullptr || decoder->context == nullptr || index < 0) {
        set_error("WavPack decoder is closed or the binary tag index is invalid");
        return nullptr;
    }
    if (maximum_bytes <= 0 || maximum_bytes > kMaxBinaryTagBytes) {
        set_error("WavPack binary tag limit must be between 1 byte and 32 MiB");
        return nullptr;
    }

    std::string name;
    if (!indexed_tag_name(decoder->context, index, true, &name)) {
        set_error("WavPack binary tag does not exist");
        return nullptr;
    }
    const int size = WavpackGetBinaryTagItem(decoder->context, name.c_str(), nullptr, 0);
    if (size <= 0) {
        set_error("WavPack binary tag is empty");
        return nullptr;
    }
    if (size > maximum_bytes || size > kMaxBinaryTagBytes) {
        set_error("WavPack binary tag exceeds the configured memory limit");
        return nullptr;
    }

    std::vector<char> value(static_cast<std::size_t>(size));
    const int actual = WavpackGetBinaryTagItem(
        decoder->context,
        name.c_str(),
        value.data(),
        size
    );
    if (actual != size) {
        set_error("WavPack binary tag could not be read completely");
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(size);
    if (result != nullptr) {
        env->SetByteArrayRegion(
            result,
            0,
            size,
            reinterpret_cast<const jbyte*>(value.data())
        );
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_wavpack_NativeWavPackBridge_nativeReadFrames(
    JNIEnv* env,
    jobject,
    jlong handle,
    jintArray output,
    jint requested_frames
) {
    last_error.clear();
    auto* decoder = from_handle(handle);
    if (decoder == nullptr || decoder->context == nullptr || output == nullptr) {
        set_error("WavPack decoder is closed or the output buffer is missing");
        return -1;
    }
    const int channels = WavpackGetNumChannels(decoder->context);
    if (requested_frames <= 0 || channels <= 0 ||
        static_cast<int64_t>(requested_frames) * channels > env->GetArrayLength(output)) {
        set_error("WavPack output buffer cannot hold the requested interleaved frames");
        return -1;
    }

    jint* samples = env->GetIntArrayElements(output, nullptr);
    if (samples == nullptr) {
        set_error("WavPack output buffer could not be mapped");
        return -1;
    }
    const int errors_before = WavpackGetNumErrors(decoder->context);
    const uint32_t decoded = WavpackUnpackSamples(
        decoder->context,
        reinterpret_cast<int32_t*>(samples),
        static_cast<uint32_t>(requested_frames)
    );
    env->ReleaseIntArrayElements(output, samples, 0);

    const int errors_after = WavpackGetNumErrors(decoder->context);
    if (errors_after > errors_before) {
        const char* message = WavpackGetErrorMessage(decoder->context);
        set_error(message == nullptr || *message == '\0'
            ? "WavPack block checksum or decode error"
            : message);
        return -1;
    }
    if (decoded == 0) {
        const int64_t total = WavpackGetNumSamples64(decoder->context);
        const int64_t current = WavpackGetSampleIndex64(decoder->context);
        if (total >= 0 && current >= 0 && current < total) {
            set_error("WavPack stream ended before the declared frame count");
            return -1;
        }
    }
    return static_cast<jint>(decoded);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_wavpack_NativeWavPackBridge_nativeSeekToFrame(
    JNIEnv*,
    jobject,
    jlong handle,
    jlong frame
) {
    last_error.clear();
    auto* decoder = from_handle(handle);
    if (decoder == nullptr || decoder->context == nullptr || frame < 0) {
        set_error("WavPack decoder is closed or the requested frame is invalid");
        return JNI_FALSE;
    }
    if (!WavpackSeekSample64(decoder->context, static_cast<int64_t>(frame))) {
        const char* message = WavpackGetErrorMessage(decoder->context);
        set_error(message == nullptr || *message == '\0'
            ? "WavPack stream could not seek to the requested frame"
            : message);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_wavpack_NativeWavPackBridge_nativeDecodeErrorCount(
    JNIEnv*,
    jobject,
    jlong handle
) {
    auto* decoder = from_handle(handle);
    return decoder == nullptr || decoder->context == nullptr
        ? -1
        : WavpackGetNumErrors(decoder->context);
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_wavpack_NativeWavPackBridge_nativeClose(
    JNIEnv*,
    jobject,
    jlong handle
) {
    destroy(from_handle(handle));
}
