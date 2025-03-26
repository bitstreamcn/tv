#include "TcpDataSource.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <chrono>
#include <cstring>
#include <system_error>
#include <android/log.h>
#include <iostream>
#include <cstdint>
#include <inttypes.h>
#include "TcpClient.h"

#define LOG_TAG "NativeTCP"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

using namespace std::chrono_literals;

RunStatus gRunStatus;

std::string formatNumberWithCommas(uint64_t num) {
    std::string numStr = std::to_string(num);
    std::string formattedStr;
    int count = 0;
    // 从字符串末尾开始遍历
    for (auto it = numStr.rbegin(); it != numStr.rend(); ++it) {
        if (count > 0 && count % 3 == 0) {
            formattedStr += ',';
        }
        formattedStr += *it;
        ++count;
    }
    // 反转字符串以恢复正确顺序
    std::reverse(formattedStr.begin(), formattedStr.end());
    return formattedStr;
}

TcpDataSource::TcpDataSource() = default;

TcpDataSource::~TcpDataSource() {
    disconnect();
}

bool TcpDataSource::connect(const char* ip, int port) {
    std::lock_guard<std::mutex> lock(socket_mutex_);

    memset(&gRunStatus, 0, sizeof(gRunStatus));
    
    server_ip_ = ip;
    server_port_ = port;

    reconnect_attempts_ = 0;
    
    if (!reconnect()) {
        if (error_callback_) {
            error_callback_("Initial connection failed");
        }
        //return false;
    }

    running_ = true;
    receiver_thread_ = std::thread(&TcpDataSource::receiverThread, this);
    //heartbeat_thread_ = std::thread(&TcpDataSource::heartbeatThread, this);
    return true;
}

void TcpDataSource::disconnect() {
    running_ = false;
    
    if (receiver_thread_.joinable()) {
        receiver_thread_.join();
    }
    
    if (heartbeat_thread_.joinable()) {
        heartbeat_thread_.join();
    }
    
    std::lock_guard<std::mutex> lock(socket_mutex_);
    if (sockfd_ != -1) {
        ::close(sockfd_);
        sockfd_ = -1;
    }
}

ssize_t TcpDataSource::read(uint8_t* buffer, size_t size) {
    return buffer_.read(buffer, size);
}

void TcpDataSource::receiverThread() {
    static const size_t RECV_BUFFER_SIZE = 188 * 1024; // 1MB
    std::vector<uint8_t> buffer(RECV_BUFFER_SIZE);
    uint32_t show_status_seconds = 0;
    while (running_) {
        {

            auto now = std::chrono::system_clock::now();
            auto timestamp = std::chrono::duration_cast<std::chrono::seconds>(now.time_since_epoch()).count();

            //每隔一段时间显示运行状态
            if ((timestamp - show_status_seconds) > 5)
            {
                /*
                std::cout << "已收到：" << gRunStatus.recv_data_size
                    << "，封包数：" << gRunStatus.recv_packages_count << "/" << gRunStatus.recv_packages_size
                    << "，队列：" << gRunStatus.buffer_size << "/" << BUFFER_SIZE << "(" << gRunStatus.buffer_usage << "%)" << std::endl;
                */
                LOGD("已收到：%s，封包数：""%s/%s，队列：%s/%s(%s%%)", formatNumberWithCommas(gRunStatus.recv_data_size).c_str(), 
                formatNumberWithCommas(gRunStatus.recv_packages_count).c_str(), formatNumberWithCommas(gRunStatus.recv_packages_size).c_str(), 
                formatNumberWithCommas(gRunStatus.buffer_size).c_str(), formatNumberWithCommas((uint64_t)BUFFER_SIZE).c_str(),
                formatNumberWithCommas(gRunStatus.buffer_usage).c_str());
                show_status_seconds = timestamp;
            }

            std::lock_guard<std::mutex> lock(socket_mutex_);
            if (sockfd_ == -1 && !reconnect()) {
                std::this_thread::sleep_for(1s);
                continue;
            }
            
            ssize_t received = ::recv(sockfd_, buffer.data(), buffer.size(), 0);
            if (received > 0) {
                if (received == buffer.size()) {
                    //收到满包，应该增加缓冲区
                    std::cout << "TcpDataSource::receiverThread buffer full" << std::endl;
                }
                gRunStatus.recv_data_size += received;
                recvBuffer.insert(recvBuffer.end(), buffer.begin(), 
                                buffer.begin() + received);
                processReceivedData();

            } else if (received == 0) {
                if (error_callback_) {
                    error_callback_("Connection closed by peer");
                }
                reconnect();
            } else {
                if (errno != EAGAIN && errno != EWOULDBLOCK) {
                    if (error_callback_) {
                        error_callback_("Receive error: " + std::string(strerror(errno)));
                    }
                    reconnect();
                }
            }
        }
        std::this_thread::sleep_for(10ms);
    }
}


bool TcpDataSource::processReceivedData() {
    const size_t HEADER_SIZE = 12; // T(4)+PID(4)+LEN(4)

    while (recvBuffer.size() >= HEADER_SIZE) {
        uint32_t magic = ntohl(*reinterpret_cast<uint32_t*>(recvBuffer.data()));
        uint32_t pid = ntohl(*reinterpret_cast<uint32_t*>(recvBuffer.data() + 4));
        uint32_t length = ntohl(*reinterpret_cast<uint32_t*>(recvBuffer.data() + 8));

        if (magic != MAGIC_T || length > MAX_PACKAGE_SIZE) {
            recvBuffer.erase(recvBuffer.begin(), recvBuffer.begin() + 4);
            //不应该出现这种情况
            LOGD("processReceivedData: magic != MAGIC_T, magic: %x, pid: %d, length: %d", magic, pid, length);
            continue;
        }

        size_t totalSize = HEADER_SIZE + length;
        if (recvBuffer.size() >= totalSize) {
            size_t written = 0;
            while (written < static_cast<size_t>(length)) {
                written += buffer_.write(recvBuffer.data() + HEADER_SIZE + written, 
                                        length - written);
                if (written < static_cast<size_t>(length)) {
                    //缓冲区满了，应该增加缓冲区
                    std::cout << "TcpDataSource::processReceivedData buffer_ full" << std::endl;
                }
            }
            recvBuffer.erase(recvBuffer.begin(), recvBuffer.begin() + totalSize);
            gRunStatus.recv_packages_count++;
            gRunStatus.recv_packages_size += length;
            gRunStatus.buffer_size = buffer_.available();
            gRunStatus.buffer_usage = static_cast<uint16_t>(gRunStatus.buffer_size * 1.0 / BUFFER_SIZE * 100);
            //LOGD("processReceivedData: pid: %d, length: %d", pid, length);
            // 立即发送响应（同步操作）
            /*
            if (!sendResponse(pid)) {
                if (error_callback_) {
                    error_callback_("response failed");
                }
            }
            */
        } else {
            break;
        }
    }
    return true;
}

void TcpDataSource::heartbeatThread() {
    constexpr uint32_t HEARTBEAT_INTERVAL = 30;
    const uint8_t heartbeat_packet[8] = {0};
    
    while (running_) {
        {
            std::lock_guard<std::mutex> lock(socket_mutex_);
            if (sockfd_ != -1) {
                if (::send(sockfd_, heartbeat_packet, sizeof(heartbeat_packet), 0) < 0) {
                    if (error_callback_) {
                        error_callback_("Heartbeat failed");
                    }
                    reconnect();
                }
            }
        }
        std::this_thread::sleep_for(std::chrono::seconds(HEARTBEAT_INTERVAL));
    }
}

// 发送响应包
bool TcpDataSource::sendResponse(uint32_t pid) {
    static constexpr size_t RESPONSE_SIZE = 9; // T(4)+PID(4)+STATUS(1)
    uint32_t net_magic = htonl(MAGIC_T);
    uint32_t net_pid = htonl(pid);
    const uint8_t status = 0x00; // 成功状态

    uint8_t response[RESPONSE_SIZE];
    memcpy(response, &net_magic, 4);
    memcpy(response + 4, &net_pid, 4);
    response[8] = status;

    return ::send(sockfd_, response, RESPONSE_SIZE, MSG_NOSIGNAL) == RESPONSE_SIZE;
}

bool TcpDataSource::reconnect() {
    if (sockfd_ != -1) {
        ::close(sockfd_);
        sockfd_ = -1;
    }

    if (sessionid == EMPTY_SESSION_ID)
    {
        return false;
    }

    sockfd_ = socket(AF_INET, SOCK_STREAM, 0);
    if (sockfd_ < 0) return false;

    sockaddr_in server_addr{};
    server_addr.sin_family = AF_INET;
    server_addr.sin_port = htons(server_port_);
    inet_pton(AF_INET, server_ip_.c_str(), &server_addr.sin_addr);

    if (::connect(sockfd_, (sockaddr*)&server_addr, sizeof(server_addr)) < 0) {
        ::close(sockfd_);
        sockfd_ = -1;
        return false;
    }

    // Set non-blocking
    int flags = fcntl(sockfd_, F_GETFL, 0);
    fcntl(sockfd_, F_SETFL, flags | O_NONBLOCK);

    uint32_t sessionid_net = htonl(sessionid);
    int res = send(sockfd_, reinterpret_cast<char*>(&sessionid_net), 4, 0);
    if (res != 4)
    {
        ::close(sockfd_);
        sockfd_ = -1;
        return false;
    }

    //buffer_.reset();
    return true;
}

void TcpDataSource::setErrorCallback(ErrorCallback cb) {
    error_callback_ = std::move(cb);
}