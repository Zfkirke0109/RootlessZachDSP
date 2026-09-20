#include <jni.h>

#include <algorithm>
#include <cerrno>
#include <climits>
#include <cstdint>
#include <cstring>
#include <iterator>
#include <string>
#include <utility>
#include <vector>

#include <unistd.h>

#define DR_FLAC_IMPLEMENTATION
#include "../libjdspimptoolbox/dr_flac.h"

namespace {

constexpr std::size_t kMaxTagCount = 512;
constexpr std::size_t kMaxSingleTagBytes = 64 * 1024;
constexpr std::size_t kMaxTotalTagBytes = 2 * 1024 * 1024;
constexpr std::size_t kMaxArtworkBytes = 4 * 1024 * 1024;

struct FlacDecoder {
    int fd = -1;
    drflac* decoder = nullptr;
    std::vector<std::string> tags;
    std::size_t tag_bytes = 0;
    std::vector<std::uint8_t> artwork;
    std::string artwork_mime;
    std::uint32_t artwork_type = DRFLAC_PICTURE_TYPE_OTHER;
    std::string stream_md5;
};

std::string bytes_to_hex(const drflac_uint8* bytes, std::size_t size) {
    static constexpr char kHex[] = "0123456789abcdef";
    std::string result(size * 2, '0');
    for (std::size_t index = 0; index < size; ++index) {
        result[index * 2] = kHex[(bytes[index] >> 4) & 0x0f];
        result[index * 2 + 1] = kHex[bytes[index] & 0x0f];
    }
    return result;
}

size_t read_from_fd(void* user_data, void* output, size_t bytes_to_read) {
    auto* source = static_cast<FlacDecoder*>(user_data);
    if (source == nullptr || source->fd < 0 || output == nullptr) return 0;

    std::size_t total = 0;
    while (total < bytes_to_read) {
        const ssize_t count = read(
            source->fd,
            static_cast<std::uint8_t*>(output) + total,
            bytes_to_read - total
        );
        if (count > 0) {
            total += static_cast<std::size_t>(count);
            continue;
        }
        if (count < 0 && errno == EINTR) continue;
        break;
    }
    return total;
}

drflac_bool32 seek_fd(void* user_data, int offset, drflac_seek_origin origin) {
    auto* source = static_cast<FlacDecoder*>(user_data);
    if (source == nullptr || source->fd < 0) return DRFLAC_FALSE;
    const int whence = origin == drflac_seek_origin_start ? SEEK_SET : SEEK_CUR;
    return lseek(source->fd, static_cast<off_t>(offset), whence) >= 0
        ? DRFLAC_TRUE
        : DRFLAC_FALSE;
}

void collect_metadata(void* user_data, drflac_metadata* metadata) {
    auto* source = static_cast<FlacDecoder*>(user_data);
    if (source == nullptr || metadata == nullptr) return;

    if (metadata->type == DRFLAC_METADATA_BLOCK_TYPE_STREAMINFO) {
        const bool has_md5 = std::any_of(
            std::begin(metadata->data.streaminfo.md5),
            std::end(metadata->data.streaminfo.md5),
            [](drflac_uint8 value) { return value != 0; }
        );
        source->stream_md5 = has_md5
            ? bytes_to_hex(metadata->data.streaminfo.md5, 16)
            : std::string();
        return;
    }

    if (metadata->type == DRFLAC_METADATA_BLOCK_TYPE_VORBIS_COMMENT) {
        drflac_vorbis_comment_iterator iterator{};
        drflac_init_vorbis_comment_iterator(
            &iterator,
            metadata->data.vorbis_comment.commentCount,
            metadata->data.vorbis_comment.pComments
        );
        while (source->tags.size() < kMaxTagCount) {
            drflac_uint32 length = 0;
            const char* comment = drflac_next_vorbis_comment(&iterator, &length);
            if (comment == nullptr) break;
            if (length == 0 || length > kMaxSingleTagBytes) continue;
            if (source->tag_bytes + length > kMaxTotalTagBytes) break;
            source->tags.emplace_back(comment, comment + length);
            source->tag_bytes += length;
        }
        return;
    }

    if (metadata->type != DRFLAC_METADATA_BLOCK_TYPE_PICTURE) return;
    const auto& picture = metadata->data.picture;
    if (picture.pPictureData == nullptr || picture.pictureDataSize == 0 ||
        picture.pictureDataSize > kMaxArtworkBytes) {
        return;
    }

    const bool current_is_front = source->artwork_type == DRFLAC_PICTURE_TYPE_COVER_FRONT;
    const bool candidate_is_front = picture.type == DRFLAC_PICTURE_TYPE_COVER_FRONT;
    if (!source->artwork.empty() && (current_is_front || !candidate_is_front)) return;

    source->artwork.assign(
        picture.pPictureData,
        picture.pPictureData + picture.pictureDataSize
    );
    if (picture.mime != nullptr && picture.mimeLength > 0) {
        source->artwork_mime.assign(picture.mime, picture.mime + picture.mimeLength);
    } else {
        source->artwork_mime.clear();
    }
    source->artwork_type = picture.type;
}

FlacDecoder* from_handle(jlong handle) {
    return reinterpret_cast<FlacDecoder*>(static_cast<std::uintptr_t>(handle));
}

jlong to_handle(FlacDecoder* decoder) {
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(decoder));
}

void destroy(FlacDecoder* source) {
    if (source == nullptr) return;
    if (source->decoder != nullptr) drflac_close(source->decoder);
    if (source->fd >= 0) close(source->fd);
    delete source;
}

jstring new_string(JNIEnv* env, const std::string& value) {
    if (value.empty()) return env->NewStringUTF("");
    if (value.size() > static_cast<std::size_t>(INT_MAX)) return nullptr;

    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(
        bytes,
        0,
        static_cast<jsize>(value.size()),
        reinterpret_cast<const jbyte*>(value.data())
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

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_flac_NativeFlacBridge_nativeIsSeekable(
    JNIEnv*, jobject, jint input_fd
) {
    if (input_fd < 0) return JNI_FALSE;
    const int owned_fd = dup(input_fd);
    if (owned_fd < 0) return JNI_FALSE;
    const bool seekable = lseek(owned_fd, 0, SEEK_CUR) >= 0;
    close(owned_fd);
    return seekable ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_flac_NativeFlacBridge_nativeOpen(
    JNIEnv*, jobject, jint input_fd
) {
    const int owned_fd = dup(input_fd);
    if (owned_fd < 0 || lseek(owned_fd, 0, SEEK_SET) < 0) {
        if (owned_fd >= 0) close(owned_fd);
        return 0;
    }

    auto* source = new FlacDecoder();
    source->fd = owned_fd;
    source->decoder = drflac_open_with_metadata(
        read_from_fd,
        seek_fd,
        collect_metadata,
        source,
        nullptr
    );
    if (source->decoder == nullptr) {
        destroy(source);
        return 0;
    }
    return to_handle(source);
}

extern "C" JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_flac_NativeFlacBridge_nativeSampleRate(
    JNIEnv*, jobject, jlong handle
) {
    const auto* source = from_handle(handle);
    return source != nullptr && source->decoder != nullptr
        ? static_cast<jint>(source->decoder->sampleRate)
        : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_flac_NativeFlacBridge_nativeChannels(
    JNIEnv*, jobject, jlong handle
) {
    const auto* source = from_handle(handle);
    return source != nullptr && source->decoder != nullptr
        ? static_cast<jint>(source->decoder->channels)
        : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_flac_NativeFlacBridge_nativeBitsPerSample(
    JNIEnv*, jobject, jlong handle
) {
    const auto* source = from_handle(handle);
    return source != nullptr && source->decoder != nullptr
        ? static_cast<jint>(source->decoder->bitsPerSample)
        : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_flac_NativeFlacBridge_nativeTotalFrames(
    JNIEnv*, jobject, jlong handle
) {
    const auto* source = from_handle(handle);
    return source != nullptr && source->decoder != nullptr
        ? static_cast<jlong>(source->decoder->totalPCMFrameCount)
        : 0;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_flac_NativeFlacBridge_nativeTags(
    JNIEnv* env, jobject, jlong handle
) {
    const auto* source = from_handle(handle);
    const jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    const jsize count = source == nullptr ? 0 : static_cast<jsize>(source->tags.size());
    jobjectArray result = env->NewObjectArray(count, string_class, nullptr);
    if (result == nullptr || source == nullptr) return result;
    for (jsize index = 0; index < count; ++index) {
        jstring value = new_string(env, source->tags[static_cast<std::size_t>(index)]);
        if (value == nullptr) break;
        env->SetObjectArrayElement(result, index, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_flac_NativeFlacBridge_nativeArtwork(
    JNIEnv* env, jobject, jlong handle
) {
    const auto* source = from_handle(handle);
    if (source == nullptr || source->artwork.empty()) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(source->artwork.size()));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(
        result,
        0,
        static_cast<jsize>(source->artwork.size()),
        reinterpret_cast<const jbyte*>(source->artwork.data())
    );
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_flac_NativeFlacBridge_nativeArtworkMime(
    JNIEnv* env, jobject, jlong handle
) {
    const auto* source = from_handle(handle);
    return new_string(env, source == nullptr ? std::string() : source->artwork_mime);
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_flac_NativeFlacBridge_nativeStreamMd5(
    JNIEnv* env, jobject, jlong handle
) {
    const auto* source = from_handle(handle);
    return new_string(env, source == nullptr ? std::string() : source->stream_md5);
}

extern "C" JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_flac_NativeFlacBridge_nativeReadFrames(
    JNIEnv* env, jobject, jlong handle, jintArray output, jint requested_frames
) {
    auto* source = from_handle(handle);
    if (source == nullptr || source->decoder == nullptr || output == nullptr || requested_frames <= 0) {
        return -1;
    }
    const jsize sample_capacity = env->GetArrayLength(output);
    const jint channel_count = static_cast<jint>(source->decoder->channels);
    if (channel_count <= 0 || sample_capacity < channel_count) return -1;
    const jint frame_capacity = sample_capacity / channel_count;
    const jint frames_to_read = std::min(requested_frames, frame_capacity);

    jint* samples = env->GetIntArrayElements(output, nullptr);
    if (samples == nullptr) return -1;
    const drflac_uint64 frames_read = drflac_read_pcm_frames_s32(
        source->decoder,
        static_cast<drflac_uint64>(frames_to_read),
        reinterpret_cast<drflac_int32*>(samples)
    );
    env->ReleaseIntArrayElements(output, samples, 0);
    return static_cast<jint>(frames_read);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_flac_NativeFlacBridge_nativeSeekToFrame(
    JNIEnv*, jobject, jlong handle, jlong frame
) {
    auto* source = from_handle(handle);
    if (source == nullptr || source->decoder == nullptr || frame < 0) return JNI_FALSE;
    return drflac_seek_to_pcm_frame(source->decoder, static_cast<drflac_uint64>(frame))
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_player_codec_flac_NativeFlacBridge_nativeClose(
    JNIEnv*, jobject, jlong handle
) {
    destroy(from_handle(handle));
}
