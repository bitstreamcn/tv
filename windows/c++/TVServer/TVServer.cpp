// TVServer.cpp : 此文件包含 "main" 函数。程序执行将在此处开始并结束。
//
#include <cstdint>
#include <vector>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <tlhelp32.h>
#include <iostream>
#include <string>
#include <cwchar>
#include <thread>
#include <queue>
#include <map>
#include <mutex>
#include <chrono>
#include "media.h"
#include "filelist.h"
#include "encode.h"
#include "session.h"

#pragma comment(lib, "ws2_32.lib")

std::mutex session_mutex;
std::map<uint32_t, Session*> session_map;

bool exit_program = false;

// 控制端口处理线程
void control_thread(SOCKET ctrl_sock) {
    // 设置读取超时时间为 5 秒
    timeval timeout;
    timeout.tv_sec = 5;
    timeout.tv_usec = 0;
    if (setsockopt(ctrl_sock, SOL_SOCKET, SO_RCVTIMEO, (const char*)&timeout, sizeof(timeout)) == SOCKET_ERROR) {
        std::cerr << "setsockopt failed: " << WSAGetLastError() << std::endl;
        closesocket(ctrl_sock);
        return;
    }
    //客户端先发送自己的sessionid，服务端查找session列表，如果不存在或者sessionid是EMPTY_SESSION_ID，就创建新的session，否则就附加到已有的session
    //服务端再回复一个sessionid
    char buff[4];
    int received = recv(ctrl_sock, buff, 4, MSG_WAITALL);
    if (received != 4) {
        closesocket(ctrl_sock);
        return;
    }
    Session* session = nullptr;
    uint32_t sessionid = ntohl(*reinterpret_cast<uint32_t*>(&buff[0]));
    if (sessionid == EMPTY_SESSION_ID)
    {
        session = new Session();
        {
            std::lock_guard<std::mutex> lock(session_mutex);
            session_map[session->GetSessionId()] = session;
        }
    }
    else {
        {
            std::lock_guard<std::mutex> lock(session_mutex);
            session = session_map[sessionid];
        }
        if (session == nullptr)
        {
            session = new Session();
            {
                std::lock_guard<std::mutex> lock(session_mutex);
                session_map[session->GetSessionId()] = session;
            }
        }
    }
    uint32_t sessionid_net = htonl(session->GetSessionId());
    send(ctrl_sock, reinterpret_cast<char*>(&sessionid_net), 4, 0);
    session->AttachCtrlSocket(ctrl_sock);
}

// 数据发送线程
void data_thread(SOCKET data_sock) {
    //客户端连接后发送sessionid
    char buff[4];
    int received = recv(data_sock, buff, 4, MSG_WAITALL);
    if (received != 4) {
        closesocket(data_sock);
        return;
    }
    uint32_t sessionid = ntohl(*reinterpret_cast<uint32_t*>(&buff[0]));
    //查找session，如果session不存在就关闭连接，否则附加到session
    Session* session = nullptr;
    {
        std::lock_guard<std::mutex> lock(session_mutex);
        session = session_map[sessionid];
    }
    if (nullptr == session)
    {
        closesocket(data_sock);
        return;
    }
    session->AttachDataSocket(data_sock);
}


void control_listen_thread(SOCKET ctrl_listen) {
    SOCKET ctrl_sock;
    // 等待连接
    while ((ctrl_sock = accept(ctrl_listen, NULL, NULL)) > 0 && !exit_program)
    {
        // 启动线程
        std::thread ctrl_handler(control_thread, ctrl_sock);
        ctrl_handler.detach();
    }
}

void data_listen_thread(SOCKET data_listen) {
    SOCKET data_sock;
    // 等待连接
    while ((data_sock = accept(data_listen, NULL, NULL)) > 0 && !exit_program)
    {
        // 启动线程
        std::thread data_handler(data_thread, data_sock);
        data_handler.detach();
    }
}

//session管理
void session_manage_thread()
{
    while (!exit_program)
    {
        //定时检查session是否完全断开，清理超时session
        std::this_thread::sleep_for(std::chrono::milliseconds(static_cast<int>(100)));
    }
}

SOCKET pingServerSocket = -1;

//广播ping线程
int ping_thread()
{
#define PORT 25325
#define BUFFER_SIZE 1024
    ;
    struct sockaddr_in serverAddr, clientAddr;
    socklen_t clientAddrLen = sizeof(clientAddr);
    char buffer[BUFFER_SIZE];

    // 创建 UDP 套接字
    if ((pingServerSocket = socket(AF_INET, SOCK_DGRAM, 0)) == -1) {
        std::cerr << "Failed to create socket." << std::endl;
        return 1;
    }

    // 启用端口复用 
    {
        int reuse = 1;
        if (setsockopt(pingServerSocket, SOL_SOCKET, SO_REUSEADDR, (char*)&reuse, sizeof(reuse)) == SOCKET_ERROR) {
            std::cerr << "Failed to setsockopt socket." << std::endl;
            closesocket(pingServerSocket);
            pingServerSocket = -1;
            return -1;
        }
    }

    // 配置服务器地址
    memset(&serverAddr, 0, sizeof(serverAddr));
    serverAddr.sin_family = AF_INET;
    serverAddr.sin_addr.s_addr = INADDR_ANY;
    serverAddr.sin_port = htons(PORT);

    // 绑定套接字到指定地址和端口
    if (bind(pingServerSocket, (struct sockaddr*)&serverAddr, sizeof(serverAddr)) == -1) {
        std::cerr << "Failed to bind socket." << std::endl;
        closesocket(pingServerSocket);
        pingServerSocket = -1;
        return 1;
    }

    std::cout << "Server is listening on port " << PORT << std::endl;

    while (!exit_program) {
        // 接收客户端广播消息
        int recvLen = recvfrom(pingServerSocket, buffer, BUFFER_SIZE, 0, (struct sockaddr*)&clientAddr, &clientAddrLen);
        if (recvLen == -1) {
            std::cerr << "Failed to receive data." << std::endl;
            continue;
        }

        buffer[recvLen] = '\0';

        char ipstr[INET_ADDRSTRLEN];
        // 假设 clientAddr 已经正确初始化
        inet_ntop(AF_INET, &(clientAddr.sin_addr), ipstr, INET_ADDRSTRLEN);
        std::cout << "Received broadcast from " << ipstr << ": " << buffer << std::endl;

        // 发送响应消息给客户端
        const char* response = "Server is here!";
        if (sendto(pingServerSocket, response, (int)strlen(response), 0, (struct sockaddr*)&clientAddr, clientAddrLen) == -1) {
            std::cerr << "Failed to send response." << std::endl;
        }
    }

    // 关闭套接字
    closesocket(pingServerSocket);
    pingServerSocket = -1;
    return 0;
}

SOCKET ctrl_listen = -1;
SOCKET data_listen = -1;

BOOL WINAPI ConsoleCtrlHandler(DWORD dwCtrlType) {
    if (dwCtrlType == CTRL_C_EVENT) {
        std::cout << "\nCustom Ctrl+C handler: Program will exit gracefully." << std::endl;
        // 执行清理操作
        if (-1 != ctrl_listen)
        {
            closesocket(ctrl_listen);
            ctrl_listen = -1;
        }
        if (-1 != data_listen)
        {
            closesocket(data_listen);
            data_listen = -1;
        }
        if (-1 != pingServerSocket)
        {
            closesocket(pingServerSocket);
            pingServerSocket = -1;
        }
        exit_program = true;
        return TRUE; // 阻止默认终止行为
    }
    return FALSE;
}

void KillProcessByName(const std::string& processName) {
    //system(("start taskkill /IM " + processName + " /T /F").c_str());
    ShellExecute(NULL, "open", "taskkill.exe", ("/IM " + processName + " /T /F").c_str(), NULL, SW_HIDE);
    /*
    HANDLE hSnapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (hSnapshot == INVALID_HANDLE_VALUE) {
        std::cerr << "CreateToolhelp32Snapshot 失败。错误代码: " << GetLastError() << std::endl;
        return;
    }

    PROCESSENTRY32 pe;
    pe.dwSize = sizeof(PROCESSENTRY32);

    if (!Process32First(hSnapshot, &pe)) {
        std::cerr << "Process32First 失败。错误代码: " << GetLastError() << std::endl;
        CloseHandle(hSnapshot);
        return;
    }

    bool found = false;
    do {
        if (_stricmp(pe.szExeFile, processName.c_str()) == 0) {
            found = true;
            HANDLE hProcess = OpenProcess(PROCESS_TERMINATE, FALSE, pe.th32ProcessID);
            if (hProcess != NULL) {
                if (TerminateProcess(hProcess, 0)) {
                    std::cout << "已终止进程: " << pe.szExeFile
                        << " (PID: " << pe.th32ProcessID << ")" << std::endl;
                }
                else {
                    DWORD error = GetLastError();
                    std::cerr << "无法终止进程 " << pe.szExeFile
                        << "。错误代码: " << error << std::endl;
                }
                CloseHandle(hProcess);
            }
            else {
                DWORD error = GetLastError();
                std::cerr << "无法打开进程 " << pe.szExeFile
                    << "。错误代码: " << error << std::endl;
            }
            
        }
    } while (Process32Next(hSnapshot, &pe));

    if (!found) {
        std::cout << "未找到进程: " << processName << std::endl;
    }

    CloseHandle(hSnapshot);
    */
}

int main() {
    std::locale commaLocale(std::locale(), new std::numpunct<char>());
    std::cout.imbue(commaLocale);
    std::cout << "TVServer..." << std::endl;

    if (!SetConsoleCtrlHandler(ConsoleCtrlHandler, TRUE)) {
        std::cerr << "Error: Cannot install handler." << std::endl;
        return 1;
    }

    // 设置 FFmpeg 日志级别为 AV_LOG_QUIET，屏蔽所有日志输出
    av_log_set_level(AV_LOG_ERROR);
    avformat_network_init();

    WSADATA wsa;
    // 初始化Winsock库
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        std::cerr << "WSAStartup failed: " << WSAGetLastError() << std::endl;
        return 1;
    }

    // 创建控制端口监听套接字
    ctrl_listen = socket(AF_INET, SOCK_STREAM, 0);
    if (ctrl_listen == INVALID_SOCKET) {
        std::cerr << "Control socket creation failed: " << WSAGetLastError() << std::endl;
        WSACleanup();
        return 1;
    }

    // 启用端口复用 
    {
        int reuse = 1;
        if (setsockopt(ctrl_listen, SOL_SOCKET, SO_REUSEADDR, (char*)&reuse, sizeof(reuse)) == SOCKET_ERROR) {
            closesocket(ctrl_listen);
            WSACleanup();
            return -1;
        }
    }

    sockaddr_in ctrl_addr{ AF_INET, htons(25313), INADDR_ANY };
    // 绑定控制端口套接字
    if (bind(ctrl_listen, (sockaddr*)&ctrl_addr, sizeof(ctrl_addr)) == SOCKET_ERROR) {
        std::cerr << "Control socket bind failed[25313]: " << WSAGetLastError() << std::endl;
        closesocket(ctrl_listen);
        WSACleanup();
        return 1;
    }

    // 开始监听控制端口
    if (listen(ctrl_listen, 5) == SOCKET_ERROR) {
        std::cerr << "Control socket listen failed: " << WSAGetLastError() << std::endl;
        closesocket(ctrl_listen);
        WSACleanup();
        return 1;
    }

    // 创建数据端口监听套接字
    data_listen = socket(AF_INET, SOCK_STREAM, 0);
    if (data_listen == INVALID_SOCKET) {
        std::cerr << "Data socket creation failed: " << WSAGetLastError() << std::endl;
        closesocket(ctrl_listen);
        WSACleanup();
        return 1;
    }

    // 启用端口复用 
    {
        int reuse = 1;
        if (setsockopt(data_listen, SOL_SOCKET, SO_REUSEADDR, (char*)&reuse, sizeof(reuse)) == SOCKET_ERROR) {
            closesocket(ctrl_listen);
            closesocket(data_listen);
            WSACleanup();
            return -1;
        }
    }

    sockaddr_in data_addr{ AF_INET, htons(25314), INADDR_ANY };
    // 绑定数据端口套接字
    if (bind(data_listen, (sockaddr*)&data_addr, sizeof(data_addr)) == SOCKET_ERROR) {
        std::cerr << "Data socket bind failed[25314]: " << WSAGetLastError() << std::endl;
        closesocket(ctrl_listen);
        closesocket(data_listen);
        WSACleanup();
        return 1;
    }

    // 开始监听数据端口
    if (listen(data_listen, 5) == SOCKET_ERROR) {
        std::cerr << "Data socket listen failed: " << WSAGetLastError() << std::endl;
        closesocket(ctrl_listen);
        closesocket(data_listen);
        WSACleanup();
        return 1;
    }

    std::cout << "listen: 25313/25314" << std::endl;

    // 启动线程
    std::thread control_listen_thread(control_listen_thread, ctrl_listen);
    std::thread data_listen_thread(data_listen_thread, data_listen);
    std::thread session_thread(session_manage_thread);
    std::thread ping_thread(ping_thread);

    control_listen_thread.join();
    data_listen_thread.join();
    session_thread.join();
    ping_thread.join();

    if (-1 != ctrl_listen)
    {
        closesocket(ctrl_listen);
        ctrl_listen = -1;
    }
    if (-1 != data_listen)
    {
        closesocket(data_listen);
        data_listen = -1;
    }
    for (auto it = session_map.begin(); it != session_map.end(); ++it) {
        //std::cout << "Key: " << it->first << ", Value: " << it->second << std::endl;
        if (it->second != nullptr)
        {
            delete it->second;
        }
    }

    

    KillProcessByName("ffmpeg.exe");

    WSACleanup();

    return 0;
}

