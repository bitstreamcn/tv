#pragma once


#include "include/nlohmann/json.hpp" // 使用 nlohmann/json 库


#include <iostream>
#include <fstream>
#include <cstdint>
#include <vector>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <thread>
#include <queue>
#include <map>
#include <mutex>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
#include <libavutil/time.h>
#include <libavutil/avutil.h>
#include <libavutil/frame.h>
}


#define TS_PACKET_SIZE 188
#define MAX_QUEUE_SIZE 1000
#define DEC_BUFF_SIZE (TS_PACKET_SIZE * 32)

// 全局共享队列
typedef struct {
    std::mutex mutex;
    std::condition_variable cv;
    std::queue<std::vector<uint8_t>> ts_packets;
} MediaQueue;

// 编码器上下文
/*
struct EncodingContext {
    // 视频部分
    AVCodecContext* video_decoder, * video_encoder;
    SwsContext* sws_ctx;
    // 音频部分
    AVCodecContext* audio_decoder, * audio_encoder;
    SwrContext* swr_ctx;
    // 复用器
    AVFormatContext* fmt_ctx;
    AVStream* video_stream, * audio_stream;

    int video_idx;
    int audio_idx;

    bool exit_decode;
};

struct PlayerControl {
    std::mutex mutex;
    std::condition_variable cv;
    bool seek_requested = false;
    int64_t seek_target_ts = 0; // 基于AV_TIME_BASE的时间戳
    bool flushing = false;
    bool exit_flag = false;
    int64_t current_pts = AV_NOPTS_VALUE;
};
*/

struct RunStatus {
    //已读封包数
    uint64_t read_packet_count = 0;
    //视频封包队列
    uint64_t video_packet_count = 0;
    //音频封包队列
    uint64_t audio_packet_count = 0;
    //视频帧队列
    uint64_t video_frame_count = 0;
    //音频帧队列
    uint64_t audio_frame_count = 0;

    //音频时间
    double audio_clock = 0;
    //视频时间
    double video_clock = 0;

    //已解码封包数
    uint64_t dec_packages_count = 0;
    //已解码字节数
    uint64_t dec_packages_size = 0;
    //发送队列封包数
    uint64_t queue_count = 0;

    //已发送封包数
    uint64_t sent_packages_count = 0;
    //已发送封包字节
    uint64_t sent_packages_size = 0;
};


class Session;

class Media {
public:
    Media(std::string inut_file, Session & s, bool ffmpeg);
    ~Media();

    bool Start(bool rawdata = false);
    bool Stop();
    bool Seek(double seconds);
    double Duration();
    double Fps() { return fps; }

private:

    static int write_packet(void* opaque, uint8_t* buf, int buf_size);
    int write_packet(uint8_t* buf, int buf_size);

    static bool MainThread(Media * This);
    bool MainCallback();

    static bool MainPipeThread(Media* This);
    bool MainPipeCallback();

    static bool MainRawThread(Media* This);
    bool MainRawCallback();

    static bool MainFileThread(Media* This);
    bool MainFileCallback();

    static bool VideoDecodeThread(Media* This);
    bool VideoDecodeCallback();
    static bool AudioDecodeThread(Media* This);
    bool AudioDecodeCallback();
    static bool VideoEncodeThread(Media* This);
    bool VideoEncodeCallback();
    static bool AudioEncodeThread(Media* This);
    bool AudioEncodeCallback();

    void ClearQueue();

    Session& session;

    std::string path_file;

    double fps = 0;
    enum AVPixelFormat pixel_format;

    std::mutex clock_mutex;
    volatile double video_clock = 0;
    volatile double audio_clock = 0;

    std::thread main_thread; //主线程
    std::thread video_decode_thread; //视频解码线程
    std::thread audio_decode_thread; //音频解码线程
    std::thread video_encode_thread; //视频解码线程
    std::thread audio_encode_thread; //音频解码线程

    const AVRational TS_TIME_BASE = { 1, 90000 }; // MPEG-TS标准时间基

    std::mutex _mutex;
    volatile bool stop_flag = true;
    AVFormatContext* input_fmt_ctx = NULL;

    volatile bool seek_requested_ = false;
    volatile int64_t seek_target_ = 0;

    int video_idx = -1;
    int audio_idx = -1;
    volatile int64_t video_start_time = AV_NOPTS_VALUE;
    volatile int64_t audio_start_time = AV_NOPTS_VALUE;

    AVFormatContext* output_ctx = NULL;
    AVIOContext* avio_ctx = nullptr;

    AVCodecContext* video_dec_ctx = nullptr;
    AVCodecContext* video_enc_ctx = nullptr;
    SwsContext* sws_ctx = nullptr;

    AVCodecContext* audio_dec_ctx = nullptr;
    AVCodecContext* audio_enc_ctx = nullptr;
    SwrContext* swr_ctx = nullptr;

    std::queue<AVPacket*> video_packet_queue;
    std::mutex video_packet_queue_mutex;
    std::condition_variable video_packet_queue_cv;

    std::queue<AVFrame*> video_frame_queue;
    std::mutex video_frame_queue_mutex;
    std::condition_variable video_frame_queue_cv;

    std::queue<AVPacket*> audio_packet_queue;
    std::mutex audio_packet_queue_mutex;
    std::condition_variable audio_packet_queue_cv;

    std::queue<AVFrame*> audio_frame_queue;
    std::mutex audio_frame_queue_mutex;
    std::condition_variable audio_frame_queue_cv;

    volatile int64_t video_last_pts = AV_NOPTS_VALUE;
    volatile int64_t audio_last_pts = AV_NOPTS_VALUE;

    std::ifstream file;
    int64_t filesize = 0;
};
