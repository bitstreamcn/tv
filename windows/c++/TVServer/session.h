#pragma once

#include "media.h"
#include <thread>
#include <queue>
#include <map>
#include <mutex>
#include <iostream>
#include <stdint.h>
#include <winsock2.h>
#include <ws2tcpip.h>

#define EMPTY_SESSION_ID 0xf1f2f3f4

class Session {
public:
	Session();
	~Session();

	uint32_t GetSessionId() { return sessionid; }
	void AttachCtrlSocket(SOCKET sock);
	void AttachDataSocket(SOCKET sock);

	MediaQueue data_queues;
	//PlayerControl ctrl;
	RunStatus run_status;
private:

	static void control_thread(Session* This);
	void control_fun();
	static void datasend_thread(Session* This);
	void datasend_fun();

	void clear_queue();

	uint32_t sessionid = 0;
	SOCKET ctrl_sock = -1;
	SOCKET data_sock = -1;

	std::atomic<bool> stream_paused = false;
	std::condition_variable cv;
	std::mutex pause_mutex;

	Media* media = nullptr;

	std::thread ctrl_thread;
	std::thread data_thread;
};

