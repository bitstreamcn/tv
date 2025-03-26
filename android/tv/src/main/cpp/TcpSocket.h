// TcpSocket.h
#pragma once
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#include <string>

class TcpSocket {
public:
    TcpSocket();
    ~TcpSocket();
    
    bool connect(const std::string& ip, int port);
    void disconnect();
    ssize_t send(const uint8_t* data, size_t length);
    ssize_t receive(uint8_t* buffer, size_t length);
    
private:
    int sockfd = -1;
    struct sockaddr_in serverAddr{};
};