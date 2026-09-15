#include <android/log.h>
#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <jni.h>
#include <memory>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#include <unordered_map>

#ifndef GRES3_SENDMMSG_PROBE_LOG_TAG
#define GRES3_SENDMMSG_PROBE_LOG_TAG "GRES3_SENDMMSG"
#endif

namespace {

constexpr int kRequestedSendBufferBytes = 262144;
constexpr int kMaxLegCount = 32;

struct BatchContext {
    int fd = -1;
    int legCount = 0;
    sockaddr_in addrs[kMaxLegCount];
    iovec iovs[kMaxLegCount];
    mmsghdr msgs[kMaxLegCount];
};

std::unordered_map<int, std::unique_ptr<BatchContext>> g_batchContexts;

jlong WallNsSince(const std::chrono::steady_clock::time_point& start) {
    const auto end = std::chrono::steady_clock::now();
    return std::chrono::duration_cast<std::chrono::nanoseconds>(end - start).count();
}

bool SetSendBuffer(int fd, int requestedBytes, int* effectiveOut) {
    if (setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &requestedBytes, sizeof(requestedBytes)) != 0) {
        return false;
    }
    if (effectiveOut != nullptr) {
        socklen_t len = sizeof(*effectiveOut);
        if (getsockopt(fd, SOL_SOCKET, SO_SNDBUF, effectiveOut, &len) != 0) {
            *effectiveOut = requestedBytes;
        }
    }
    return true;
}

jintArray MakeResult(JNIEnv* env, jlong wallNs, jint returnedMessages, jint errnoValue) {
    jintArray out = env->NewIntArray(3);
    if (out == nullptr) {
        return nullptr;
    }
    jint values[3] = {returnedMessages, static_cast<jint>(wallNs), errnoValue};
    env->SetIntArrayRegion(out, 0, 3, values);
    return out;
}

bool BuildBatchContext(
    JNIEnv* env,
    jobjectArray destIps,
    jintArray destPorts,
    BatchContext* context) {
    const jsize legCount = env->GetArrayLength(destIps);
    if (legCount <= 0 || legCount > kMaxLegCount) {
        errno = EINVAL;
        return false;
    }
    if (env->GetArrayLength(destPorts) != legCount) {
        errno = EINVAL;
        return false;
    }

    jint* ports = env->GetIntArrayElements(destPorts, nullptr);
    if (ports == nullptr) {
        errno = ENOMEM;
        return false;
    }

    std::memset(context->addrs, 0, sizeof(context->addrs));
    std::memset(context->iovs, 0, sizeof(context->iovs));
    std::memset(context->msgs, 0, sizeof(context->msgs));
    context->legCount = static_cast<int>(legCount);

    for (jsize i = 0; i < legCount; ++i) {
        auto ip = static_cast<jstring>(env->GetObjectArrayElement(destIps, i));
        if (ip == nullptr) {
            env->ReleaseIntArrayElements(destPorts, ports, JNI_ABORT);
            errno = EINVAL;
            return false;
        }
        const char* ipChars = env->GetStringUTFChars(ip, nullptr);
        if (ipChars == nullptr) {
            env->DeleteLocalRef(ip);
            env->ReleaseIntArrayElements(destPorts, ports, JNI_ABORT);
            errno = ENOMEM;
            return false;
        }

        context->addrs[i].sin_family = AF_INET;
        context->addrs[i].sin_port = htons(static_cast<uint16_t>(ports[i]));
        if (inet_pton(AF_INET, ipChars, &context->addrs[i].sin_addr) != 1) {
            env->ReleaseStringUTFChars(ip, ipChars);
            env->DeleteLocalRef(ip);
            env->ReleaseIntArrayElements(destPorts, ports, JNI_ABORT);
            errno = EINVAL;
            return false;
        }

        context->iovs[i].iov_base = nullptr;
        context->iovs[i].iov_len = 0;
        context->msgs[i].msg_hdr.msg_name = &context->addrs[i];
        context->msgs[i].msg_hdr.msg_namelen = sizeof(sockaddr_in);
        context->msgs[i].msg_hdr.msg_iov = &context->iovs[i];
        context->msgs[i].msg_hdr.msg_iovlen = 1;
        context->msgs[i].msg_hdr.msg_control = nullptr;
        context->msgs[i].msg_hdr.msg_controllen = 0;
        context->msgs[i].msg_hdr.msg_flags = 0;
        context->msgs[i].msg_len = 0;

        env->ReleaseStringUTFChars(ip, ipChars);
        env->DeleteLocalRef(ip);
    }

    env->ReleaseIntArrayElements(destPorts, ports, JNI_ABORT);
    return true;
}

void ReleaseBatchContext(int fd) {
    g_batchContexts.erase(fd);
}

jintArray SendmmsgBatchCached(
    JNIEnv* env,
    jint fd,
    jbyteArray payload,
    jint payloadLen,
    int flags) {
    if (fd < 0) {
        errno = EBADF;
        return MakeResult(env, 0L, 0, errno);
    }

    const auto contextIt = g_batchContexts.find(fd);
    if (contextIt == g_batchContexts.end() || contextIt->second == nullptr) {
        errno = EINVAL;
        return MakeResult(env, 0L, 0, errno);
    }
    BatchContext* context = contextIt->second.get();
    if (context->legCount <= 0) {
        errno = EINVAL;
        return MakeResult(env, 0L, 0, errno);
    }

    jbyte* payloadBytes = env->GetByteArrayElements(payload, nullptr);
    if (payloadBytes == nullptr) {
        errno = ENOMEM;
        return MakeResult(env, 0L, 0, errno);
    }

    for (int i = 0; i < context->legCount; ++i) {
        context->iovs[i].iov_base = payloadBytes;
        context->iovs[i].iov_len = static_cast<size_t>(payloadLen);
    }

    const auto start = std::chrono::steady_clock::now();
    const int returned =
        sendmmsg(fd, context->msgs, static_cast<unsigned int>(context->legCount), flags);
    const jlong wallNs = WallNsSince(start);

    env->ReleaseByteArrayElements(payload, payloadBytes, JNI_ABORT);

    if (returned < 0) {
        __android_log_print(
            ANDROID_LOG_WARN,
            GRES3_SENDMMSG_PROBE_LOG_TAG,
            "sendmmsg failed errno=%d",
            errno);
        return MakeResult(env, wallNs, 0, errno);
    }

    return MakeResult(env, wallNs, returned, 0);
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_talkback_core_conference_capacity_Gres3SendmmsgNative_nativeOpen(
    JNIEnv* env,
    jobject /*thiz*/,
    jint bindPort,
    jobjectArray destIps,
    jintArray destPorts) {
    const int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) {
        return nullptr;
    }

    int defaultSendBuffer = 0;
    socklen_t defaultLen = sizeof(defaultSendBuffer);
    getsockopt(fd, SOL_SOCKET, SO_SNDBUF, &defaultSendBuffer, &defaultLen);

    int effectiveSendBuffer = kRequestedSendBufferBytes;
    if (!SetSendBuffer(fd, kRequestedSendBufferBytes, &effectiveSendBuffer)) {
        close(fd);
        return nullptr;
    }

    sockaddr_in bindAddr {};
    bindAddr.sin_family = AF_INET;
    bindAddr.sin_addr.s_addr = htonl(INADDR_ANY);
    bindAddr.sin_port = htons(static_cast<uint16_t>(bindPort));
    if (bind(fd, reinterpret_cast<sockaddr*>(&bindAddr), sizeof(bindAddr)) != 0) {
        close(fd);
        return nullptr;
    }

    auto context = std::make_unique<BatchContext>();
    context->fd = fd;
    if (!BuildBatchContext(env, destIps, destPorts, context.get())) {
        close(fd);
        return nullptr;
    }

    sockaddr_in localAddr {};
    socklen_t localLen = sizeof(localAddr);
    if (getsockname(fd, reinterpret_cast<sockaddr*>(&localAddr), &localLen) != 0) {
        close(fd);
        return nullptr;
    }

    g_batchContexts[fd] = std::move(context);

    const jint localPort = ntohs(localAddr.sin_port);
    jintArray out = env->NewIntArray(4);
    if (out == nullptr) {
        ReleaseBatchContext(fd);
        close(fd);
        return nullptr;
    }
    jint values[4] = {fd, localPort, defaultSendBuffer, effectiveSendBuffer};
    env->SetIntArrayRegion(out, 0, 4, values);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_talkback_core_conference_capacity_Gres3SendmmsgNative_nativeClose(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint fd) {
    if (fd >= 0) {
        ReleaseBatchContext(fd);
        close(fd);
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_talkback_core_conference_capacity_Gres3SendmmsgNative_nativeSendmmsgBatch(
    JNIEnv* env,
    jobject /*thiz*/,
    jint fd,
    jbyteArray payload,
    jint payloadLen) {
    return SendmmsgBatchCached(env, fd, payload, payloadLen, 0);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_talkback_core_conference_capacity_Gres3SendmmsgNative_nativeSendmmsgBatchNonblocking(
    JNIEnv* env,
    jobject /*thiz*/,
    jint fd,
    jbyteArray payload,
    jint payloadLen) {
    return SendmmsgBatchCached(env, fd, payload, payloadLen, MSG_DONTWAIT);
}
