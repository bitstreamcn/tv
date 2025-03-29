#include "session.h"

#include "include/nlohmann/json.hpp" // 使用 nlohmann/json 库

#include <cstdint>
#include <vector>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <chrono>

#include "filelist.h"
#include "encode.h"
#include "session.h"

#include <iostream>
#include <filesystem>
#include <string>

namespace fs = std::filesystem;

static std::mutex _mutex;
static uint32_t autoinc_id = 1000;

const uint32_t MAGIC_T = 0x7a321465;
const uint32_t MAGIC_T_DOWNLOAD = 0x7a321466;

void log(std::string s)
{
    std::cout << s << std::endl;
}

void loghex(const char* hexArray, int arraySize)
{
    for (int i = 0; i < arraySize; ++i) {
        // 使用 %02X 确保每个字节以两位十六进制数输出
        std::printf("%02X ", hexArray[i]);
    }
    std::cout << std::endl;
}


// 接收完整TLV数据包
std::string receive_tlv_packet(SOCKET sock) {
    std::vector<char> buffer;

    // Step 1: 读取T和L头部 (8字节)
    char magic_code[4] = { 0, 0, 0, 0 };
    char len_buff[4] = { 0, 0, 0, 0 };
    int received;
    while (true)
    {
        received = recv(sock, magic_code, 4, MSG_WAITALL);
        if (received == SOCKET_ERROR) {
            int errorCode = WSAGetLastError();
            if (errorCode == WSAETIMEDOUT) {
                //std::cout << "Read timeout occurred." << std::endl;
                return "";
            }
            else {
                throw std::runtime_error("socket error");
            }
        }
        if (received == 0)
        {
            //对方已经关闭连接
            throw std::runtime_error("socket error");
        }
        if (received != 4)
        {
            std::cout << "received < 4." << std::endl;
            return "";
        }
        // 解析T和L (网络字节序转主机序)
        uint32_t t = ntohl(*reinterpret_cast<uint32_t*>(&magic_code[0]));

        // 校验魔数T
        if (t != MAGIC_T) {
            loghex(magic_code, 4);
            std::cout << "Invalid T value." << std::endl;
            continue;
        }
        else {
            break;
        }
    }
    uint32_t l = 0;
    do
    {
        received = recv(sock, len_buff, 4, MSG_WAITALL);
        if (received == SOCKET_ERROR) {
            int errorCode = WSAGetLastError();
            if (errorCode == WSAETIMEDOUT) {
                //std::cout << "Read timeout occurred." << std::endl;
                return "";
            }
            else {
                throw std::runtime_error("socket error");
            }
        }
        if (received == 0)
        {
            //对方已经关闭连接
            throw std::runtime_error("socket error");
        }
        if (received != 4)
        {
            loghex(len_buff, 4);
            std::cout << "Invalid length." << std::endl;
            return "";
        }
        l = ntohl(*reinterpret_cast<uint32_t*>(&len_buff[0]));
    } while (l == MAGIC_T);

    if (l > 1024)
    {
        std::cout << "Invalid length value:" << l << std::endl;
        return "";
    }

    // Step 2: 读取V数据
    buffer.resize(l);
    uint32_t data_size = 0;
    while (data_size < l)
    {
        int recv_size = recv(sock, buffer.data() + data_size, l - data_size, MSG_WAITALL);
        if (recv_size <= 0) {
            throw std::runtime_error("socket error");
        }
        data_size += recv_size;
    }
    return std::string(buffer.data(), l);
}

void send_tlv_packet(SOCKET sock, const nlohmann::json& json) {
    std::string json_str = json.dump();

    //log("发送数据:" + UTF8ToGB2312(json_str));

    // 构造TLV
    uint32_t t_net = htonl(MAGIC_T);
    uint32_t l_net = htonl(static_cast<uint32_t>(json_str.size()));

    // 发送头部
    //send(sock, reinterpret_cast<char*>(&t_net), 4, 0); //重复发送，因为android DataOutputStream/DataInputStream会吃掉4个字节
    send(sock, reinterpret_cast<char*>(&t_net), 4, 0);

    send(sock, reinterpret_cast<char*>(&l_net), 4, 0);

    // 发送数据
    send(sock, json_str.data(), (int)json_str.size(), 0);
}

bool send_file(SOCKET sock, const std::string filename) {

    // 以二进制模式打开文件
    std::ifstream file(filename, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        std::cerr << "无法打开文件: " << filename << std::endl;
        return false;
    }

    // 获取文件大小
    std::streamsize filesize = file.tellg();
    file.seekg(0, std::ios::beg); // 重置文件指针到文件开头

    // 创建一个缓冲区来存储文件内容
    std::vector<char> buffer(filesize);

    // 读取文件内容到缓冲区
    if (!file.read(buffer.data(), filesize)) {
        std::cerr << "读取文件失败: " << filename << std::endl;
        file.close();
        return false;
    }

    // 构造TLV
    uint32_t t_net = htonl(MAGIC_T_DOWNLOAD);
    uint32_t l_net = htonl(static_cast<uint32_t>(filesize));

    // 发送头部
    //send(sock, reinterpret_cast<char*>(&t_net), 4, 0); //重复发送，因为android DataOutputStream/DataInputStream会吃掉4个字节
    send(sock, reinterpret_cast<char*>(&t_net), 4, 0);

    send(sock, reinterpret_cast<char*>(&l_net), 4, 0);

    // 发送数据
    std::streamsize sent = 0;
    bool haserr = false;
    while (sent < filesize)
    {
        int ret = send(sock, buffer.data() + sent, (int)(filesize - sent), 0);
        if (ret == SOCKET_ERROR) {
            int error = WSAGetLastError();
            if (error == WSAEWOULDBLOCK) {
                // 发送缓冲区满，稍后重试
                std::cout << "发送缓冲区满，稍后重试..." << std::endl;
                // 这里可以添加一些延迟，然后再次尝试发送
                // 例如：Sleep(100); // 注意：Sleep函数的参数是毫秒
                Sleep(100);
                continue;
            }
            else {
                // 其他错误处理
                std::cerr << "发送数据失败，错误码: " << error << std::endl;
                haserr = true;
                break;
            }
        }
        else if (ret == 0) {
            // 连接已关闭
            std::cerr << "连接已关闭" << std::endl;
            haserr = true;
            break;
        }
        sent += ret;
    }
    // 关闭文件
    file.close();

    return !haserr;
}

Session::Session()
{
	{
		std::lock_guard<std::mutex> lock(_mutex);
		sessionid = autoinc_id;
		autoinc_id++;
		if (EMPTY_SESSION_ID == autoinc_id)
		{
			autoinc_id++;
		}
	}
}
Session::~Session()
{
	if (ctrl_sock != -1)
	{
		//关闭socket并等待读线程结束
		closesocket(ctrl_sock);
        ctrl_sock = -1;
	}
    if (ctrl_thread.joinable())
    {
        ctrl_thread.join();
    }
	if (data_sock != -1)
	{
		//关闭socket并等待读线程结束
		closesocket(data_sock);
        data_sock = -1;
	}
    stream_paused = false;
    cv.notify_all();
    {
        std::unique_lock<std::mutex> lock(data_queues.mutex);
        data_queues.ts_packets.push(std::move(std::vector<uint8_t>()));
    }
    data_queues.cv.notify_all();
    if (data_thread.joinable())
    {
        data_thread.join();
    }
    if (nullptr != media)
    {
        delete media;
        media = nullptr;
    }
}

void Session::AttachCtrlSocket(SOCKET sock)
{
	if (ctrl_sock != -1)
	{
		//关闭socket并等待读线程结束
		closesocket(ctrl_sock);
        ctrl_sock = -1;
	}
    if (ctrl_thread.joinable())
    {
        ctrl_thread.join();
    }
	ctrl_sock = sock; 
	//启动接收线程
	ctrl_thread = std::thread(Session::control_thread, this);
}
void Session::AttachDataSocket(SOCKET sock)
{ 
	if (data_sock != -1)
	{
		//关闭socket并等待读线程结束
		closesocket(data_sock);
        data_sock = -1;
	}
    stream_paused = false;
    cv.notify_all();
    {
        std::unique_lock<std::mutex> lock(data_queues.mutex);
        data_queues.ts_packets.push(std::move(std::vector<uint8_t>()));
    }
    data_queues.cv.notify_all();
    if (data_thread.joinable())
    {
        data_thread.join();
    }
	data_sock = sock; 
	//启动接收线程
    data_thread = std::thread(Session::datasend_thread, this);
}

void Session::control_thread(Session* This)
{
	This->control_fun();
}

bool isTsFile(const std::string& path) {
    fs::path p(UTF8ToGB2312(path));
    std::string ext = p.extension().string();
    // 统一转换为小写
    for (char& c : ext) c = std::tolower(c);
    return (ext == ".ts");
}

void Session::clear_queue()
{
    {
        std::lock_guard<std::mutex> lock(data_queues.mutex);
        while (!data_queues.ts_packets.empty()) {
            data_queues.ts_packets.pop();
        }
    }
}

void Session::control_fun()
{
    while (true) {
        try {
            std::string json_str = receive_tlv_packet(ctrl_sock);
            if (json_str == "")
            {
                continue;
            }
            //log("收到数据:" + json_str);

            auto cmd = nlohmann::json::parse(json_str);

            nlohmann::json response;
            response["status"] = "fail";

            if (!cmd.contains("action"))
            {
                // 发送响应
                send_tlv_packet(ctrl_sock, response);
                continue;
            }

            if (cmd["action"] == "pause") {
                int buffer_time = cmd["buffer_time"];
                if (buffer_time > 20000) {
                    if (!stream_paused)
                    {
                        log("暂停发送，客户端缓冲区：" + std::to_string(buffer_time));
                    }
                    stream_paused = true;
                }
            }
            else if (cmd["action"] == "resume") {
                int buffer_time = cmd["buffer_time"];
                if (buffer_time < 10000) {
                    if (stream_paused)
                    {
                        log("恢复发送，客户端缓冲区：" + std::to_string(buffer_time));
                    }
                    stream_paused = false;
                    cv.notify_all();
                }
            }
            else if (cmd["action"] == "stop_stream") {
                log("stop_stream");
                memset(&run_status, 0, sizeof(run_status));

                if (nullptr != media) {
                    delete media;
                    media = nullptr;
                }
                clear_queue();
            }
            else if (cmd["action"] == "stream") {
                std::string path = cmd["path"]; //返回的是UTF-8格式
                double pts = cmd["start_time"];
                log("stream：" + UTF8ToGB2312(path + std::string(" - ") + std::to_string(pts)));
                memset(&run_status, 0, sizeof(run_status));

                bool raw = isTsFile(path);
                if (nullptr != media) {
                    delete media;
                    media = nullptr;
                }
                clear_queue();
                media = new Media(path, *this);
                media->Seek(pts);
                media->Start(true);
                response["status"] = "success";
                response["message"] = "";
                response["duration"] = media->Duration();
            }
            else if (cmd["action"] == "list") {
                std::string currentPath = cmd["path"];
                response = getDirectoryContent(currentPath);
                std::string json_str = response.dump();
                //log(json_str);
            }
            else if (cmd["action"] == "seek") {
                memset(&run_status, 0, sizeof(run_status));

                double  pts = cmd["pts"];
                std::string path = cmd["path"]; //返回的是UTF-8格式
                log("seek：" + UTF8ToGB2312(path) + std::string(" - ") + std::to_string(pts));
                bool raw = isTsFile(path);
                if (nullptr != media) {
                    delete media;
                    media = nullptr;
                }
                clear_queue();
                media = new Media(path, *this);
                media->Seek(pts);
                media->Start(true);
                response["status"] = "success";
                response["message"] = "";
                response["duration"] = media->Duration();
            }
            else if (cmd["action"] == "enc" || cmd["action"] == "encode") {
                std::string path = cmd["path"]; //返回的是UTF-8格式
                bool ists = isTsFile(path);
                if (ists)
                {
                    response["status"] = "fail";
                    response["message"] = "无需要转码";
                }
                else
                {
                    std::string pathgb2312 = UTF8ToGB2312(path);
                    // 获取文件所在目录和文件名（不包含扩展名）
                    std::filesystem::path fsPath(pathgb2312);
                    std::string directory = fsPath.parent_path().string();
                    std::string filenameWithoutExt = fsPath.stem().string();
                    std::string outputPath = directory + "\\" + filenameWithoutExt + ".ts";

                    // 构建 ffmpeg 命令
                    std::string ffmpegCommand = "ffmpeg -y -re -i \"" + pathgb2312 + "\" -c:v libx264 -preset slow -tune film -crf 23 -bufsize 6M -maxrate 5M -b:v 2M -c:a aac -b:a 160k -f mpegts \"" + outputPath + "\"";
                    //std::string ffmpegCommand = "ffmpeg -y -i \"" + pathgb2312 + "\" -c copy -f mpegts \"" + outputPath + "\"";

                    STARTUPINFO si = { sizeof(si) };
                    PROCESS_INFORMATION pi;
                    // 创建新进程执行 FFmpeg 命令
                    if (CreateProcess(NULL, const_cast<char*>(ffmpegCommand.c_str()), NULL, NULL, FALSE, 0, NULL, NULL, &si, &pi)) {
                        std::cout << "FFmpeg process started." << std::endl;

                        // 等待进程结束
                        //WaitForSingleObject(pi.hProcess, INFINITE);

                        // 关闭进程和线程句柄
                        CloseHandle(pi.hProcess);
                        CloseHandle(pi.hThread);
                    }
                    else {
                        std::cerr << "Failed to start FFmpeg process. Error code: " << GetLastError() << std::endl;
                    }

                    response["status"] = "success";
                }
            }
            else if (cmd["action"] == "aac5.1") {
                std::string path = cmd["path"]; //返回的是UTF-8格式
                bool ists = isTsFile(path);
                if (ists)
                {
                    response["status"] = "fail";
                    response["message"] = "无需要转码";
                }
                else
                {
                    std::string pathgb2312 = UTF8ToGB2312(path);
                    // 获取文件所在目录和文件名（不包含扩展名）
                    std::filesystem::path fsPath(pathgb2312);
                    std::string directory = fsPath.parent_path().string();
                    std::string filenameWithoutExt = fsPath.stem().string();
                    std::string outputPath = directory + "\\" + filenameWithoutExt + "_stereo.mp4";

                    // 构建 ffmpeg 命令
                    std::string ffmpegCommand = "ffmpeg -y -i \"" + pathgb2312 + "\" -map 0 -c:v copy -c:a aac -ac 2 -b:a 192k \"" + outputPath + "\"";

                    std::cout << ffmpegCommand << std::endl;

                    STARTUPINFO si = { sizeof(si) };
                    PROCESS_INFORMATION pi;
                    // 创建新进程执行 FFmpeg 命令
                    if (CreateProcess(NULL, const_cast<char*>(ffmpegCommand.c_str()), NULL, NULL, FALSE, 0, NULL, NULL, &si, &pi)) {
                        std::cout << "FFmpeg process started." << std::endl;

                        // 等待进程结束
                        //WaitForSingleObject(pi.hProcess, INFINITE);

                        // 关闭进程和线程句柄
                        CloseHandle(pi.hProcess);
                        CloseHandle(pi.hThread);
                    }
                    else {
                        std::cerr << "Failed to start FFmpeg process. Error code: " << GetLastError() << std::endl;
                    }

                    response["status"] = "success";
                }
            }
            else if (cmd["action"] == "smblist") {
                nlohmann::json list = nlohmann::json::array();
                nlohmann::json server;
                server["name"] = "bitstream";
                server["ip"] = "192.168.2.80";
                server["user"] = "tv";
                server["password"] = "tv";
                list.push_back(server);

                response["status"] = "success";
                response["message"] = "";
                response["items"] = list;
            }
            else if (cmd["action"] == "download") {
                std::string path = cmd["path"]; //返回的是UTF-8格式
                std::string pathgb2312 = UTF8ToGB2312(path);
                if (send_file(ctrl_sock, pathgb2312))
                {
                    continue;
                }
            }
            // 发送响应
            send_tlv_packet(ctrl_sock, response);
        }
        catch (const std::exception& e)
        {
            log(e.what());
            break;
        }
    }
}

void Session::datasend_thread(Session* This)
{
    This->datasend_fun();
}
void Session::datasend_fun()
{
    uint8_t package[TS_PACKET_SIZE] = { 0 };
    int reserve_size = 0;
    uint32_t packageid = 1;
    printf("播放数据通道已连接\n");
    uint32_t show_status_seconds = 0;
    int times = 0;
    while (true) {
        {
            std::unique_lock<std::mutex> lk(pause_mutex);
            cv.wait(lk, [this] { return !stream_paused; });
        }
        std::vector<uint8_t> currentData;
        {
            std::unique_lock<std::mutex> lock(data_queues.mutex);
            data_queues.cv.wait(lock, [this] { return!data_queues.ts_packets.empty(); });
            if (!data_queues.ts_packets.empty()) {
                currentData = std::move(data_queues.ts_packets.front());
                data_queues.ts_packets.pop();
                run_status.sent_packages_count++;
            }
        }
        if (-1 == data_sock)
        {
            break;
        }
        if (currentData.size() == 0)
        {
            continue;
        }

        auto now = std::chrono::system_clock::now();
        auto timestamp = std::chrono::duration_cast<std::chrono::seconds>(now.time_since_epoch()).count();

        //每隔一段时间显示运行状态
        if ((timestamp - show_status_seconds) > 5)
        {
            std::cout << "[" << sessionid << "]"
                << "已读封包数：" << run_status.read_packet_count
                << "，音频时间：" << run_status.audio_clock
                << "，视频时间：" << run_status.video_clock
                << "，视频封包队列：" << run_status.video_packet_count
                << "，音频封包队列：" << run_status.audio_packet_count
                << "，视频帧队列：" << run_status.video_frame_count
                << "，音频帧队列：" << run_status.audio_frame_count
                << "，已解码：" << run_status.dec_packages_count << "/" << run_status.sent_packages_size
                << "，已发送：" << run_status.sent_packages_count << "/" << run_status.sent_packages_size
                << "，队列：" << run_status.queue_count << std::endl;
            show_status_seconds = (uint32_t)timestamp;
        }

        if (packageid > 0x7FFFFF00)
        {
            packageid = 1;
        }

        // 构造TLV
        uint32_t t_net = htonl(MAGIC_T);

        uint8_t* ts_data = currentData.data();
        int ts_size = (int)currentData.size();
        if ((reserve_size + ts_size) >= TS_PACKET_SIZE)
        {
            //先发送未发送部分
            if (reserve_size > 0)
            {
                // 发送头部
                //send(data_sock, reinterpret_cast<char*>(&t_net), 4, 0); //重复发送，因为android DataOutputStream/DataInputStream会吃掉4个字节
                send(data_sock, reinterpret_cast<char*>(&t_net), 4, 0);

                //发送包ID
                packageid++;
                uint32_t pid_net = htonl(packageid);
                int isent = send(data_sock, (char*)&pid_net, 4, 0);
                if (isent < 0) {
                    printf("连接已断开1\n");
                    closesocket(data_sock);
                    return;
                }
                if (isent != 4) {
                    printf("发送包ID失败\n");
                }
                //发送数据长度
                uint32_t pack_len = htonl(TS_PACKET_SIZE);
                isent = send(data_sock, (char*)&pack_len, 4, 0);
                if (isent < 0) {
                    printf("连接已断开1\n");
                    closesocket(data_sock);
                    return;
                }
                if (isent != 4) {
                    printf("发送包长度失败\n");
                }
                {
                    uint8_t* pdata = package;
                    int sent_count = 0;
                    while (sent_count < reserve_size)
                    {
                        int isent = send(data_sock, (char*)pdata + sent_count, reserve_size - sent_count, 0);
                        if (isent < 0) {
                            printf("连接已断开2\n");
                            closesocket(data_sock);
                            return;
                        }
                        sent_count += isent;
                    }
                }
                //发送包后部分
                {
                    int after_size = TS_PACKET_SIZE - reserve_size;
                    uint8_t* pdata = ts_data;
                    int sent_count = 0;
                    while (sent_count < after_size)
                    {
                        int isent = send(data_sock, (char*)pdata + sent_count, after_size - sent_count, 0);
                        if (isent < 0) {
                            printf("连接已断开3\n");
                            closesocket(data_sock);
                            return;
                        }
                        sent_count += isent;
                    }
                    ts_data += after_size;
                    ts_size -= after_size;
                }
                reserve_size = 0;
                //printf("发送数据包成功:%d\n", packageid);
                //等待响应
                /*
                uint32_t m_recv = 0;
                do {
                    int iread = recv(data_sock, (char*)&m_recv, 4, 0);
                    if (iread == -1) {
                        printf("连接已断开4\n");
                        closesocket(data_sock);
                        return;
                    }
                    if (iread != 4) {
                        printf("接收包ID失败\n");
                        break;
                    }
                } while (m_recv == t_net);
                uint32_t pid_net_recv = m_recv;

                uint32_t pid_recv = ntohl(pid_net_recv);
                if (pid_recv != packageid)
                {
                    printf("包ID响应错误\n");
                }
                //返回状态码
                uint8_t status_code = 0;
                int iread = recv(data_sock, (char*)&status_code, 1, 0);
                if (iread == -1) {
                    printf("连接已断开5\n");
                    closesocket(data_sock);
                    return;
                }
                //printf("收到响应成功:%d\n", status_code);
                */
            }

            //发送一大块数据
            {
                int div = (ts_size / TS_PACKET_SIZE);
                int pack_size = div * TS_PACKET_SIZE;
                if (pack_size > 0)
                {
                    // 发送头部
                    //send(data_sock, reinterpret_cast<char*>(&t_net), 4, 0); //重复发送，因为android DataOutputStream/DataInputStream会吃掉4个字节
                    send(data_sock, reinterpret_cast<char*>(&t_net), 4, 0);

                    //发送包ID
                    packageid++;
                    uint32_t pid_net = htonl(packageid);
                    int isent = send(data_sock, (char*)&pid_net, 4, 0);
                    if (isent < 0) {
                        printf("连接已断开1\n");
                        closesocket(data_sock);
                        return;
                    }
                    if (isent != 4) {
                        printf("发送包ID失败\n");
                    }
                    //isent = send(data_sock, (char*)&pid_net, 4, 0); //重复发送，因为android DataOutputStream/DataInputStream会吃掉4个字节
                    //发送数据长度
                    uint32_t pack_len = htonl(pack_size);
                    isent = send(data_sock, (char*)&pack_len, 4, 0);
                    if (isent < 0) {
                        printf("连接已断开1\n");
                        closesocket(data_sock);
                        return;
                    }
                    if (isent != 4) {
                        printf("发送包长度失败\n");
                    }
                    uint8_t* pdata = ts_data;
                    int sent_count = 0;
                    while (sent_count < pack_size)
                    {
                        int isent = send(data_sock, (char*)pdata + sent_count, pack_size - sent_count, 0);
                        if (isent < 0) {
                            printf("连接已断开7\n");
                            closesocket(data_sock);
                            return;
                        }
                        sent_count += isent;
                    }
                    run_status.sent_packages_size += sent_count;
                    //printf("发送数据包成功:%d\n", packageid);
                    //等待响应
                    /*
                    uint32_t m_recv = 0;
                    do {
                        int iread = recv(data_sock, (char*)&m_recv, 4, 0);
                        if (iread == -1) {
                            printf("连接已断开4\n");
                            closesocket(data_sock);
                            return;
                        }
                        if (iread != 4) {
                            printf("接收包ID失败\n");
                            break;
                        }
                    } while (m_recv == t_net);
                    uint32_t pid_net_recv = m_recv;

                    uint32_t pid_recv = ntohl(pid_net_recv);
                    if (pid_recv != packageid)
                    {
                        printf("包ID响应错误\n");
                    }
                    //返回状态码
                    uint8_t status_code = 0;
                    int iread = recv(data_sock, (char*)&status_code, 1, 0);
                    if (iread == -1) {
                        printf("连接已断开5\n");
                        closesocket(data_sock);
                        return;
                    }
                    */
                }
                if (pack_size != ts_size)
                {
                    reserve_size = ts_size - pack_size;
                    memcpy(package, ts_data + pack_size, reserve_size);
                }
            }
        }
        else
        {
            memcpy(&package[reserve_size], ts_data, ts_size);
            reserve_size += ts_size;
        }
        times++;
        if (times % 10 == 0)
        {
            //std::this_thread::sleep_for(std::chrono::milliseconds(static_cast<int>(1)));
        }
    }
}
