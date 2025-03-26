// native-lib.cpp
#include <jni.h>
#include <android/log.h>
#include "TcpClient.h"
#include "TcpDataSource.h"
#include <iostream>
#include <cstring>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <sys/time.h>
#include <unordered_set>

#define LOG_TAG "NativeTCP"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)


#define PORT 25325
#define BROADCAST_IP "255.255.255.255"
#define BUFFER_SIZE 1024

extern "C" JNIEXPORT void JNICALL
Java_com_zlang_tv_TcpControlClient_nativeSendBroadcastAndReceive(JNIEnv *env, jobject thiz, jobject list, jint timeoutSeconds) {
    int clientSocket;
    struct sockaddr_in serverAddr;
    char buffer[BUFFER_SIZE];
    int broadcastEnable = 1;

    // 创建 UDP 套接字
    if ((clientSocket = socket(AF_INET, SOCK_DGRAM, 0)) == -1) {
        std::cerr << "Failed to create socket." << std::endl;
        return;
    }

    // 设置套接字选项以允许广播
    if (setsockopt(clientSocket, SOL_SOCKET, SO_BROADCAST, &broadcastEnable, sizeof(broadcastEnable)) == -1) {
        std::cerr << "Failed to set socket option for broadcast." << std::endl;
        close(clientSocket);
        return;
    }

    // 设置接收超时时间为 3 秒
    struct timeval timeout;
    timeout.tv_sec = 3;
    timeout.tv_usec = 0;
    if (setsockopt(clientSocket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == -1) {
        std::cerr << "Failed to set receive timeout." << std::endl;
        close(clientSocket);
        return;
    }

    // 配置服务器地址
    memset(&serverAddr, 0, sizeof(serverAddr));
    serverAddr.sin_family = AF_INET;
    serverAddr.sin_addr.s_addr = inet_addr(BROADCAST_IP);
    serverAddr.sin_port = htons(PORT);

    // 发送广播消息
    const char* broadcastMessage = "Looking for server!";
    if (sendto(clientSocket, broadcastMessage, strlen(broadcastMessage), 0, (struct sockaddr*)&serverAddr, sizeof(serverAddr)) == -1) {
        std::cerr << "Failed to send broadcast message." << std::endl;
        close(clientSocket);
        return;
    }

    std::cout << "Broadcast message sent." << std::endl;

    // 获取 ArrayList 类和相关方法
    jclass listClass = env->GetObjectClass(list);
    jmethodID addMethod = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");

    // 用于检查 IP 重复
    std::unordered_set<std::string> ipSet;
    // 获取开始时间
    std::time_t startTime = std::time(nullptr);
    while (true)
    {
        // 计算已过去的时间
        std::time_t currentTime = std::time(nullptr);
        if (currentTime - startTime >= static_cast<std::time_t>(timeoutSeconds)) {
            std::cout << "Receive timed out after " << timeoutSeconds << " seconds." << std::endl;
            break;
        }
        // 接收服务器响应
        socklen_t serverAddrLen = sizeof(serverAddr);
        ssize_t recvLen = recvfrom(clientSocket, buffer, BUFFER_SIZE, 0, (struct sockaddr*)&serverAddr, &serverAddrLen);
        if (recvLen == -1) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                std::cout << "Receive timed out after 3 seconds." << std::endl;
                break;
            } else {
                std::cerr << "Failed to receive server response: " << strerror(errno) << std::endl;
                break;
            }
        } else {
            buffer[recvLen] = '\0';
            std::string serverIp = inet_ntoa(serverAddr.sin_addr);
            // 检查 IP 是否重复
            if (ipSet.find(serverIp) == ipSet.end()) {
                std::cout << "Received response from " << serverIp << ": " << buffer << std::endl;

                // 将服务器地址添加到 ArrayList 中
                jstring serverAddress = env->NewStringUTF(serverIp.c_str());
                env->CallBooleanMethod(list, addMethod, serverAddress);
                env->DeleteLocalRef(serverAddress);

                // 将 IP 加入集合
                ipSet.insert(serverIp);
            }
        }
    }

    // 关闭套接字
    close(clientSocket);
}


extern "C" JNIEXPORT jlong JNICALL
Java_com_zlang_tv_TcpControlClient_nativeCreateClient(JNIEnv* /*env*/, jclass /*clazz*/) {
    return reinterpret_cast<jlong>(new TcpClient());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zlang_tv_TcpControlClient_nativeConnect(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jstring ip, jint port) {
    
    const char* c_ip = env->GetStringUTFChars(ip, nullptr);
    TcpClient* client = reinterpret_cast<TcpClient*>(handle);
    bool success = client->connect(c_ip, static_cast<int>(port));
    env->ReleaseStringUTFChars(ip, c_ip);
    
    return static_cast<jboolean>(success);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zlang_tv_TcpControlClient_nativeDisconnect(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    
    TcpClient* client = reinterpret_cast<TcpClient*>(handle);
    client->disconnect();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_zlang_tv_TcpControlClient_nativeSendRequest(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jbyteArray data) {
    
    TcpClient* client = reinterpret_cast<TcpClient*>(handle);
    
    jsize length = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    
    std::vector<uint8_t> request(bytes, bytes + length);
    bool sendSuccess = client->sendRequest(request);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    
    if (!sendSuccess) {
        return nullptr;
    }
    
    std::vector<uint8_t> response;
    if (client->receiveResponse(response)) {
        while(client->receiveResponse(response, 0)); //取出所有返回数据，避免出现返回上一次请求结果
        jbyteArray result = env->NewByteArray(response.size());
        env->SetByteArrayRegion(result, 0, response.size(), 
                               reinterpret_cast<jbyte*>(response.data()));
        return result;
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zlang_tv_TcpControlClient_nativeDestroyClient(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    
    delete reinterpret_cast<TcpClient*>(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zlang_tv_TcpControlClient_nativeisRunning(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    TcpClient* client = reinterpret_cast<TcpClient*>(handle);
    
    return static_cast<jboolean>(client->isRunning());
}


extern "C" JNIEXPORT jlong JNICALL
Java_com_zlang_tv_TcpDataSource_nativeCreate(JNIEnv* env, jclass clazz) {
    return reinterpret_cast<jlong>(new TcpDataSource());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zlang_tv_TcpDataSource_nativeConnect(
    JNIEnv* env, jclass clazz, jlong handle, jstring ip, jint port) {
    
    const char* c_ip = env->GetStringUTFChars(ip, nullptr);
    TcpDataSource* ds = reinterpret_cast<TcpDataSource*>(handle);
    bool success = ds->connect(c_ip, static_cast<int>(port));
    env->ReleaseStringUTFChars(ip, c_ip);
    
    return static_cast<jboolean>(success);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zlang_tv_TcpDataSource_nativeRead(
    JNIEnv* env, jclass clazz, jlong handle, jbyteArray buffer, jint offset, jint length) {
    
    TcpDataSource* ds = reinterpret_cast<TcpDataSource*>(handle);
    jbyte* bytes = env->GetByteArrayElements(buffer, nullptr);
    //jsize length = env->GetArrayLength(buffer);
    
    ssize_t read = ds->read(reinterpret_cast<uint8_t*>(bytes) + offset, static_cast<size_t>(length));
    
    env->ReleaseByteArrayElements(buffer, bytes, 0);
    return static_cast<jint>(read);
}


extern "C" JNIEXPORT void JNICALL
Java_com_zlang_tv_TcpDataSource_nativeDestroy(
    JNIEnv* env, jclass clazz, jlong handle) {
    TcpDataSource* ds = reinterpret_cast<TcpDataSource*>(handle);
    ds->disconnect();
    delete ds;
}
