#include "session.h"

#include "include/nlohmann/json.hpp" // ʹ�� nlohmann/json ��

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
        // ʹ�� %02X ȷ��ÿ���ֽ�����λʮ�����������
        std::printf("%02X ", hexArray[i]);
    }
    std::cout << std::endl;
}


// ��������TLV���ݰ�
std::string receive_tlv_packet(SOCKET sock) {
    std::vector<char> buffer;

    // Step 1: ��ȡT��Lͷ�� (8�ֽ�)
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
            //�Է��Ѿ��ر�����
            throw std::runtime_error("socket error");
        }
        if (received != 4)
        {
            std::cout << "received < 4." << std::endl;
            return "";
        }
        // ����T��L (�����ֽ���ת������)
        uint32_t t = ntohl(*reinterpret_cast<uint32_t*>(&magic_code[0]));

        // У��ħ��T
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
            //�Է��Ѿ��ر�����
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

    // Step 2: ��ȡV����
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

    //log("��������:" + UTF8ToGB2312(json_str));

    // ����TLV
    uint32_t t_net = htonl(MAGIC_T);
    uint32_t l_net = htonl(static_cast<uint32_t>(json_str.size()));

    // ����ͷ��
    //send(sock, reinterpret_cast<char*>(&t_net), 4, 0); //�ظ����ͣ���Ϊandroid DataOutputStream/DataInputStream��Ե�4���ֽ�
    send(sock, reinterpret_cast<char*>(&t_net), 4, 0);

    send(sock, reinterpret_cast<char*>(&l_net), 4, 0);

    // ��������
    send(sock, json_str.data(), (int)json_str.size(), 0);
}

bool send_file(SOCKET sock, const std::string filename) {

    // �Զ�����ģʽ���ļ�
    std::ifstream file(filename, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        std::cerr << "�޷����ļ�: " << filename << std::endl;
        return false;
    }

    // ��ȡ�ļ���С
    std::streamsize filesize = file.tellg();
    file.seekg(0, std::ios::beg); // �����ļ�ָ�뵽�ļ���ͷ

    // ����һ�����������洢�ļ�����
    std::vector<char> buffer(filesize);

    // ��ȡ�ļ����ݵ�������
    if (!file.read(buffer.data(), filesize)) {
        std::cerr << "��ȡ�ļ�ʧ��: " << filename << std::endl;
        file.close();
        return false;
    }

    // ����TLV
    uint32_t t_net = htonl(MAGIC_T_DOWNLOAD);
    uint32_t l_net = htonl(static_cast<uint32_t>(filesize));

    // ����ͷ��
    //send(sock, reinterpret_cast<char*>(&t_net), 4, 0); //�ظ����ͣ���Ϊandroid DataOutputStream/DataInputStream��Ե�4���ֽ�
    send(sock, reinterpret_cast<char*>(&t_net), 4, 0);

    send(sock, reinterpret_cast<char*>(&l_net), 4, 0);

    // ��������
    std::streamsize sent = 0;
    bool haserr = false;
    while (sent < filesize)
    {
        int ret = send(sock, buffer.data() + sent, (int)(filesize - sent), 0);
        if (ret == SOCKET_ERROR) {
            int error = WSAGetLastError();
            if (error == WSAEWOULDBLOCK) {
                // ���ͻ����������Ժ�����
                std::cout << "���ͻ����������Ժ�����..." << std::endl;
                // ����������һЩ�ӳ٣�Ȼ���ٴγ��Է���
                // ���磺Sleep(100); // ע�⣺Sleep�����Ĳ����Ǻ���
                Sleep(100);
                continue;
            }
            else {
                // ����������
                std::cerr << "��������ʧ�ܣ�������: " << error << std::endl;
                haserr = true;
                break;
            }
        }
        else if (ret == 0) {
            // �����ѹر�
            std::cerr << "�����ѹر�" << std::endl;
            haserr = true;
            break;
        }
        sent += ret;
    }
    // �ر��ļ�
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
		//�ر�socket���ȴ����߳̽���
		closesocket(ctrl_sock);
        ctrl_sock = -1;
	}
    if (ctrl_thread.joinable())
    {
        ctrl_thread.join();
    }
	if (data_sock != -1)
	{
		//�ر�socket���ȴ����߳̽���
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
		//�ر�socket���ȴ����߳̽���
		closesocket(ctrl_sock);
        ctrl_sock = -1;
	}
    if (ctrl_thread.joinable())
    {
        ctrl_thread.join();
    }
	ctrl_sock = sock; 
	//���������߳�
	ctrl_thread = std::thread(Session::control_thread, this);
}
void Session::AttachDataSocket(SOCKET sock)
{ 
	if (data_sock != -1)
	{
		//�ر�socket���ȴ����߳̽���
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
	//���������߳�
    data_thread = std::thread(Session::datasend_thread, this);
}

void Session::control_thread(Session* This)
{
	This->control_fun();
}

bool isTsFile(const std::string& path) {
    fs::path p(UTF8ToGB2312(path));
    std::string ext = p.extension().string();
    // ͳһת��ΪСд
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
            //log("�յ�����:" + json_str);

            auto cmd = nlohmann::json::parse(json_str);

            nlohmann::json response;
            response["status"] = "fail";

            if (!cmd.contains("action"))
            {
                // ������Ӧ
                send_tlv_packet(ctrl_sock, response);
                continue;
            }

            if (cmd["action"] == "pause") {
                int buffer_time = cmd["buffer_time"];
                if (buffer_time > 20000) {
                    if (!stream_paused)
                    {
                        log("��ͣ���ͣ��ͻ��˻�������" + std::to_string(buffer_time));
                    }
                    stream_paused = true;
                }
            }
            else if (cmd["action"] == "resume") {
                int buffer_time = cmd["buffer_time"];
                if (buffer_time < 10000) {
                    if (stream_paused)
                    {
                        log("�ָ����ͣ��ͻ��˻�������" + std::to_string(buffer_time));
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
                std::string path = cmd["path"]; //���ص���UTF-8��ʽ
                double pts = cmd["start_time"];
                log("stream��" + UTF8ToGB2312(path + std::string(" - ") + std::to_string(pts)));
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
                std::string path = cmd["path"]; //���ص���UTF-8��ʽ
                log("seek��" + UTF8ToGB2312(path) + std::string(" - ") + std::to_string(pts));
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
                std::string path = cmd["path"]; //���ص���UTF-8��ʽ
                bool ists = isTsFile(path);
                if (ists)
                {
                    response["status"] = "fail";
                    response["message"] = "����Ҫת��";
                }
                else
                {
                    std::string pathgb2312 = UTF8ToGB2312(path);
                    // ��ȡ�ļ�����Ŀ¼���ļ�������������չ����
                    std::filesystem::path fsPath(pathgb2312);
                    std::string directory = fsPath.parent_path().string();
                    std::string filenameWithoutExt = fsPath.stem().string();
                    std::string outputPath = directory + "\\" + filenameWithoutExt + ".ts";

                    // ���� ffmpeg ����
                    std::string ffmpegCommand = "ffmpeg -y -re -i \"" + pathgb2312 + "\" -c:v libx264 -preset slow -tune film -crf 23 -bufsize 6M -maxrate 5M -b:v 2M -c:a aac -b:a 160k -f mpegts \"" + outputPath + "\"";
                    //std::string ffmpegCommand = "ffmpeg -y -i \"" + pathgb2312 + "\" -c copy -f mpegts \"" + outputPath + "\"";

                    STARTUPINFO si = { sizeof(si) };
                    PROCESS_INFORMATION pi;
                    // �����½���ִ�� FFmpeg ����
                    if (CreateProcess(NULL, const_cast<char*>(ffmpegCommand.c_str()), NULL, NULL, FALSE, 0, NULL, NULL, &si, &pi)) {
                        std::cout << "FFmpeg process started." << std::endl;

                        // �ȴ����̽���
                        //WaitForSingleObject(pi.hProcess, INFINITE);

                        // �رս��̺��߳̾��
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
                std::string path = cmd["path"]; //���ص���UTF-8��ʽ
                bool ists = isTsFile(path);
                if (ists)
                {
                    response["status"] = "fail";
                    response["message"] = "����Ҫת��";
                }
                else
                {
                    std::string pathgb2312 = UTF8ToGB2312(path);
                    // ��ȡ�ļ�����Ŀ¼���ļ�������������չ����
                    std::filesystem::path fsPath(pathgb2312);
                    std::string directory = fsPath.parent_path().string();
                    std::string filenameWithoutExt = fsPath.stem().string();
                    std::string outputPath = directory + "\\" + filenameWithoutExt + "_stereo.mp4";

                    // ���� ffmpeg ����
                    std::string ffmpegCommand = "ffmpeg -y -i \"" + pathgb2312 + "\" -map 0 -c:v copy -c:a aac -ac 2 -b:a 192k \"" + outputPath + "\"";

                    std::cout << ffmpegCommand << std::endl;

                    STARTUPINFO si = { sizeof(si) };
                    PROCESS_INFORMATION pi;
                    // �����½���ִ�� FFmpeg ����
                    if (CreateProcess(NULL, const_cast<char*>(ffmpegCommand.c_str()), NULL, NULL, FALSE, 0, NULL, NULL, &si, &pi)) {
                        std::cout << "FFmpeg process started." << std::endl;

                        // �ȴ����̽���
                        //WaitForSingleObject(pi.hProcess, INFINITE);

                        // �رս��̺��߳̾��
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
                std::string path = cmd["path"]; //���ص���UTF-8��ʽ
                std::string pathgb2312 = UTF8ToGB2312(path);
                if (send_file(ctrl_sock, pathgb2312))
                {
                    continue;
                }
            }
            // ������Ӧ
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
    printf("��������ͨ��������\n");
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

        //ÿ��һ��ʱ����ʾ����״̬
        if ((timestamp - show_status_seconds) > 5)
        {
            std::cout << "[" << sessionid << "]"
                << "�Ѷ��������" << run_status.read_packet_count
                << "����Ƶʱ�䣺" << run_status.audio_clock
                << "����Ƶʱ�䣺" << run_status.video_clock
                << "����Ƶ������У�" << run_status.video_packet_count
                << "����Ƶ������У�" << run_status.audio_packet_count
                << "����Ƶ֡���У�" << run_status.video_frame_count
                << "����Ƶ֡���У�" << run_status.audio_frame_count
                << "���ѽ��룺" << run_status.dec_packages_count << "/" << run_status.sent_packages_size
                << "���ѷ��ͣ�" << run_status.sent_packages_count << "/" << run_status.sent_packages_size
                << "�����У�" << run_status.queue_count << std::endl;
            show_status_seconds = (uint32_t)timestamp;
        }

        if (packageid > 0x7FFFFF00)
        {
            packageid = 1;
        }

        // ����TLV
        uint32_t t_net = htonl(MAGIC_T);

        uint8_t* ts_data = currentData.data();
        int ts_size = (int)currentData.size();
        if ((reserve_size + ts_size) >= TS_PACKET_SIZE)
        {
            //�ȷ���δ���Ͳ���
            if (reserve_size > 0)
            {
                // ����ͷ��
                //send(data_sock, reinterpret_cast<char*>(&t_net), 4, 0); //�ظ����ͣ���Ϊandroid DataOutputStream/DataInputStream��Ե�4���ֽ�
                send(data_sock, reinterpret_cast<char*>(&t_net), 4, 0);

                //���Ͱ�ID
                packageid++;
                uint32_t pid_net = htonl(packageid);
                int isent = send(data_sock, (char*)&pid_net, 4, 0);
                if (isent < 0) {
                    printf("�����ѶϿ�1\n");
                    closesocket(data_sock);
                    return;
                }
                if (isent != 4) {
                    printf("���Ͱ�IDʧ��\n");
                }
                //�������ݳ���
                uint32_t pack_len = htonl(TS_PACKET_SIZE);
                isent = send(data_sock, (char*)&pack_len, 4, 0);
                if (isent < 0) {
                    printf("�����ѶϿ�1\n");
                    closesocket(data_sock);
                    return;
                }
                if (isent != 4) {
                    printf("���Ͱ�����ʧ��\n");
                }
                {
                    uint8_t* pdata = package;
                    int sent_count = 0;
                    while (sent_count < reserve_size)
                    {
                        int isent = send(data_sock, (char*)pdata + sent_count, reserve_size - sent_count, 0);
                        if (isent < 0) {
                            printf("�����ѶϿ�2\n");
                            closesocket(data_sock);
                            return;
                        }
                        sent_count += isent;
                    }
                }
                //���Ͱ��󲿷�
                {
                    int after_size = TS_PACKET_SIZE - reserve_size;
                    uint8_t* pdata = ts_data;
                    int sent_count = 0;
                    while (sent_count < after_size)
                    {
                        int isent = send(data_sock, (char*)pdata + sent_count, after_size - sent_count, 0);
                        if (isent < 0) {
                            printf("�����ѶϿ�3\n");
                            closesocket(data_sock);
                            return;
                        }
                        sent_count += isent;
                    }
                    ts_data += after_size;
                    ts_size -= after_size;
                }
                reserve_size = 0;
                //printf("�������ݰ��ɹ�:%d\n", packageid);
                //�ȴ���Ӧ
                /*
                uint32_t m_recv = 0;
                do {
                    int iread = recv(data_sock, (char*)&m_recv, 4, 0);
                    if (iread == -1) {
                        printf("�����ѶϿ�4\n");
                        closesocket(data_sock);
                        return;
                    }
                    if (iread != 4) {
                        printf("���հ�IDʧ��\n");
                        break;
                    }
                } while (m_recv == t_net);
                uint32_t pid_net_recv = m_recv;

                uint32_t pid_recv = ntohl(pid_net_recv);
                if (pid_recv != packageid)
                {
                    printf("��ID��Ӧ����\n");
                }
                //����״̬��
                uint8_t status_code = 0;
                int iread = recv(data_sock, (char*)&status_code, 1, 0);
                if (iread == -1) {
                    printf("�����ѶϿ�5\n");
                    closesocket(data_sock);
                    return;
                }
                //printf("�յ���Ӧ�ɹ�:%d\n", status_code);
                */
            }

            //����һ�������
            {
                int div = (ts_size / TS_PACKET_SIZE);
                int pack_size = div * TS_PACKET_SIZE;
                if (pack_size > 0)
                {
                    // ����ͷ��
                    //send(data_sock, reinterpret_cast<char*>(&t_net), 4, 0); //�ظ����ͣ���Ϊandroid DataOutputStream/DataInputStream��Ե�4���ֽ�
                    send(data_sock, reinterpret_cast<char*>(&t_net), 4, 0);

                    //���Ͱ�ID
                    packageid++;
                    uint32_t pid_net = htonl(packageid);
                    int isent = send(data_sock, (char*)&pid_net, 4, 0);
                    if (isent < 0) {
                        printf("�����ѶϿ�1\n");
                        closesocket(data_sock);
                        return;
                    }
                    if (isent != 4) {
                        printf("���Ͱ�IDʧ��\n");
                    }
                    //isent = send(data_sock, (char*)&pid_net, 4, 0); //�ظ����ͣ���Ϊandroid DataOutputStream/DataInputStream��Ե�4���ֽ�
                    //�������ݳ���
                    uint32_t pack_len = htonl(pack_size);
                    isent = send(data_sock, (char*)&pack_len, 4, 0);
                    if (isent < 0) {
                        printf("�����ѶϿ�1\n");
                        closesocket(data_sock);
                        return;
                    }
                    if (isent != 4) {
                        printf("���Ͱ�����ʧ��\n");
                    }
                    uint8_t* pdata = ts_data;
                    int sent_count = 0;
                    while (sent_count < pack_size)
                    {
                        int isent = send(data_sock, (char*)pdata + sent_count, pack_size - sent_count, 0);
                        if (isent < 0) {
                            printf("�����ѶϿ�7\n");
                            closesocket(data_sock);
                            return;
                        }
                        sent_count += isent;
                    }
                    run_status.sent_packages_size += sent_count;
                    //printf("�������ݰ��ɹ�:%d\n", packageid);
                    //�ȴ���Ӧ
                    /*
                    uint32_t m_recv = 0;
                    do {
                        int iread = recv(data_sock, (char*)&m_recv, 4, 0);
                        if (iread == -1) {
                            printf("�����ѶϿ�4\n");
                            closesocket(data_sock);
                            return;
                        }
                        if (iread != 4) {
                            printf("���հ�IDʧ��\n");
                            break;
                        }
                    } while (m_recv == t_net);
                    uint32_t pid_net_recv = m_recv;

                    uint32_t pid_recv = ntohl(pid_net_recv);
                    if (pid_recv != packageid)
                    {
                        printf("��ID��Ӧ����\n");
                    }
                    //����״̬��
                    uint8_t status_code = 0;
                    int iread = recv(data_sock, (char*)&status_code, 1, 0);
                    if (iread == -1) {
                        printf("�����ѶϿ�5\n");
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
