// TcpClient.cpp
#include "TcpClient.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <errno.h>
#include <thread>
#include <chrono>

using namespace std::chrono_literals;



uint32_t sessionid = EMPTY_SESSION_ID;

TcpClient::TcpClient() {
    sockfd = socket(AF_INET, SOCK_STREAM, 0);
    int flags = fcntl(sockfd, F_GETFL, 0);
    fcntl(sockfd, F_SETFL, flags | O_NONBLOCK);
}

TcpClient::~TcpClient() {
    disconnect();
}

bool TcpClient::connect(const char* ip, int port) {
    sockaddr_in serverAddr{};
    serverAddr.sin_family = AF_INET;
    serverAddr.sin_port = htons(port);
    inet_pton(AF_INET, ip, &serverAddr.sin_addr);

    int res = ::connect(sockfd, (sockaddr*)&serverAddr, sizeof(serverAddr));
    if (res < 0 && errno != EINPROGRESS) {
        return false;
    }

    // Wait for connection
    timeval timeout{.tv_sec = 5, .tv_usec = 0};
    fd_set set;
    FD_ZERO(&set);
    FD_SET(sockfd, &set);

    if (select(sockfd + 1, nullptr, &set, nullptr, &timeout) <= 0) {
        return false;
    }

    int error = 0;
    socklen_t len = sizeof(error);
    getsockopt(sockfd, SOL_SOCKET, SO_ERROR, &error, &len);
    if (error != 0) {
        return false;
    }

    uint32_t sessionid_net = htonl(sessionid);
    res = send(sockfd, reinterpret_cast<char*>(&sessionid_net), 4, 0);
    if (res != 4)
    {
        return false;
    }
    char buff[4];
    int recv_size = 0;
    int times = 0;
    do {
        res = recv(sockfd, &buff[recv_size], 4 - recv_size, 0);
        if (res <= 0)
        {
            if (errno != EAGAIN && errno != EWOULDBLOCK) {
                return false;
            }
            std::this_thread::sleep_for(10ms);
        }
        else
        {
            recv_size += res;
        }
        times++;
    }while(recv_size < 4 && times < 100);
    if (recv_size == 4) {
        uint32_t _sessionid = ntohl(*reinterpret_cast<uint32_t *>(&buff[0]));
        if (_sessionid != sessionid) {
            //服务端创建了新的session
        }
        sessionid = _sessionid;
    }
    running = true;
    std::thread(&TcpClient::receiveThreadFunc, this).detach();
    return true;
}

void TcpClient::disconnect() {
    running = false;
    if (sockfd != -1) {
        shutdown(sockfd, SHUT_RDWR);
        close(sockfd);
        sockfd = -1;
    }
}

bool TcpClient::sendRequest(const std::vector<uint8_t>& data) {
    std::lock_guard<std::mutex> lock(mutex);
    
    // Build TLV packet
    const uint32_t MAGIC_T = 0x7a321465;
    uint32_t type = htonl(MAGIC_T);
    uint32_t length = htonl(static_cast<uint32_t>(data.size()));
    
    std::vector<uint8_t> packet;
    packet.insert(packet.end(), reinterpret_cast<uint8_t*>(&type), 
                  reinterpret_cast<uint8_t*>(&type) + 4);
    packet.insert(packet.end(), reinterpret_cast<uint8_t*>(&length),
                  reinterpret_cast<uint8_t*>(&length) + 4);
    packet.insert(packet.end(), data.begin(), data.end());

    size_t totalSent = 0;
    while (totalSent < packet.size() && running) {
        ssize_t sent = send(sockfd, packet.data() + totalSent, 
                           packet.size() - totalSent, MSG_NOSIGNAL);
        if (sent < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                std::this_thread::sleep_for(10ms);
                continue;
            }
            return false;
        }
        totalSent += sent;
    }
    return totalSent == packet.size();
}

void TcpClient::receiveThreadFunc() {
    std::vector<uint8_t> buffer(BUFFER_SIZE);
    
    while (running) {
        ssize_t received = recv(sockfd, buffer.data(), buffer.size(), 0);
        if (received > 0) {
            std::lock_guard<std::mutex> lock(mutex);
            recvBuffer.insert(recvBuffer.end(), buffer.begin(), 
                             buffer.begin() + received);
            processReceivedData();
        } else if (received == 0) {
            disconnect();
            break;
        } else {
            if (errno != EAGAIN && errno != EWOULDBLOCK) {
                disconnect();
                break;
            }
            std::this_thread::sleep_for(10ms);
        }
    }
}

bool TcpClient::processReceivedData() {
    const size_t HEADER_SIZE = 8;
    const uint32_t MAGIC_T = 0x7a321465;

    while (recvBuffer.size() >= HEADER_SIZE) {
        uint32_t type = ntohl(*reinterpret_cast<uint32_t*>(recvBuffer.data()));
        uint32_t length = ntohl(*reinterpret_cast<uint32_t*>(recvBuffer.data() + 4));

        if (type != MAGIC_T) {
            recvBuffer.erase(recvBuffer.begin(), recvBuffer.begin() + 4);
            continue;
        }

        size_t totalSize = HEADER_SIZE + length;
        if (recvBuffer.size() >= totalSize) {
            std::vector<uint8_t> response(
                recvBuffer.begin() + HEADER_SIZE,
                recvBuffer.begin() + totalSize
            );
            responseQueue.push(std::move(response));
            recvBuffer.erase(recvBuffer.begin(), recvBuffer.begin() + totalSize);
            cv.notify_all();
        } else {
            break;
        }
    }
    return true;
}

bool TcpClient::receiveResponse(std::vector<uint8_t>& outData, int timeoutMs) {
    std::unique_lock<std::mutex> lock(mutex);
    if (cv.wait_for(lock, std::chrono::milliseconds(timeoutMs), 
        [this]{ return !responseQueue.empty(); })) {
        outData = std::move(responseQueue.front());
        responseQueue.pop();
        return true;
    }
    return false;
}