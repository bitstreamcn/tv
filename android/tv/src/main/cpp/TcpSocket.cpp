// TcpSocket.cpp
#include "TcpSocket.h"
#include <cstring>
#include <arpa/inet.h>

TcpSocket::TcpSocket() {
    sockfd = socket(AF_INET, SOCK_STREAM, 0);
}

TcpSocket::~TcpSocket() {
    disconnect();
}

bool TcpSocket::connect(const std::string& ip, int port) {
    serverAddr.sin_family = AF_INET;
    serverAddr.sin_port = htons(port);
    inet_pton(AF_INET, ip.c_str(), &serverAddr.sin_addr);
    
    return ::connect(sockfd, (struct sockaddr*)&serverAddr, sizeof(serverAddr)) == 0;
}

void TcpSocket::disconnect() {
    if (sockfd != -1) {
        close(sockfd);
        sockfd = -1;
    }
}

ssize_t TcpSocket::send(const uint8_t* data, size_t length) {
    return ::send(sockfd, data, length, 0);
}

ssize_t TcpSocket::receive(uint8_t* buffer, size_t length) {
    return recv(sockfd, buffer, length, 0);
}
