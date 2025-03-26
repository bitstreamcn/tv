#pragma once

#include "CircularBuffer.h"
#include <thread>
#include <atomic>
#include <functional>
#include <mutex>
#include <chrono>

struct RunStatus {
    //已收到字节数
    uint64_t recv_data_size = 0;
    //已收到封包数
    uint64_t recv_packages_count = 0;
    //已收到封包字节数
    uint64_t recv_packages_size = 0;
    //环形缓冲区字节数
    uint64_t buffer_size = 0;
    //环形缓冲区使用率
    uint64_t buffer_usage = 0;
};


class TcpDataSource {
public:

    // 协议常量
    static constexpr uint32_t MAGIC_T = 0x7a321465;

    static constexpr size_t BUFFER_SIZE = 188 * 64 * 1024; //  188*64KB
    static constexpr size_t MAX_PACKAGE_SIZE = 188 * 1024; // 188KB
    
    TcpDataSource();
    ~TcpDataSource();
    
    bool connect(const char* ip, int port);
    void disconnect();
    bool isConnected() const;
    ssize_t read(uint8_t* buffer, size_t size);
    
    // 心跳和错误回调
    using ErrorCallback = std::function<void(const std::string&)>;
    void setErrorCallback(ErrorCallback cb);

private:
    void receiverThread();
    void heartbeatThread();
    bool reconnect();
    bool processReceivedData();

    bool sendResponse(uint32_t pid);
    


    CircularBuffer buffer_{BUFFER_SIZE};

    // 连接相关
    mutable std::mutex socket_mutex_;
    int sockfd_ = -1;
    std::string server_ip_;
    int server_port_ = 25314;
    std::atomic<bool> running_{false};
    
    // 线程管理
    std::thread receiver_thread_;
    std::thread heartbeat_thread_;
    

    
    // 错误处理
    ErrorCallback error_callback_;
    
    // 重连控制
    std::atomic<int> reconnect_attempts_{0};
    static constexpr int MAX_RECONNECT_ATTEMPTS = 5;
    static constexpr auto RECONNECT_INTERVAL = std::chrono::seconds(3);
    std::vector<uint8_t> recvBuffer;
};