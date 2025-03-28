// TcpClient.h
#pragma once
#include <vector>
#include <mutex>
#include <condition_variable>
#include <queue>
#include <atomic>
#include <iostream>
#include <fstream>

#define EMPTY_SESSION_ID 0xf1f2f3f4

extern uint32_t sessionid;

class TcpClient {
public:
    TcpClient();
    ~TcpClient();
    
    bool connect(const char* ip, int port);
    void disconnect();
    bool sendRequest(const std::vector<uint8_t>& data, bool download = false, const std::string filename = "");
    bool receiveResponse(std::vector<uint8_t>& outData, int timeoutMs = 5000);
    inline bool isRunning(){return running;}
    
private:
    void receiveThreadFunc();
    bool processReceivedData();
    
    int sockfd = -1;
    std::atomic<bool> running{false};
    std::mutex mutex;
    std::condition_variable cv;
    std::queue<std::vector<uint8_t>> responseQueue;
    std::vector<uint8_t> recvBuffer;
    static const size_t BUFFER_SIZE = 1024 * 1024; // 1MB

    bool isdownload = false;
    std::ofstream file;
    bool begin_download = false;
    uint32_t filesize = 0;
    uint32_t writesize = 0;
};