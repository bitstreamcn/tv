#include "media.h"
#include "encode.h"

#include <iostream>
#include <fstream>
#include <stdio.h>
#include <inttypes.h>
#include "session.h"


Media::Media(std::string inut_file, Session& s)
    :session(s)
{
    path_file = inut_file;
    if (avformat_open_input(&input_fmt_ctx, inut_file.c_str(), NULL, NULL) != 0)
    {
        std::cout << "avformat_open_input fail" << std::endl;
        return;
    }
    if (avformat_find_stream_info(input_fmt_ctx, NULL) < 0)
    {
        std::cout << "avformat_find_stream_info fail" << std::endl;
        avformat_close_input(&input_fmt_ctx);
        return;
    }
    // 查找视频和音频流
    //*
    video_idx = av_find_best_stream(input_fmt_ctx, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    audio_idx = av_find_best_stream(input_fmt_ctx, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);

    if (video_idx < 0 && audio_idx < 0) {
        fprintf(stderr, "未找到音视频流\n");
        avformat_close_input(&input_fmt_ctx);
        return;
    }
    //*/
}


Media::~Media()
{
    Stop();

    if (nullptr != input_fmt_ctx)
    {
        avformat_close_input(&input_fmt_ctx);
    }
}

double Media::Duration()
{
    /*
    if (nullptr == input_fmt_ctx || -1 == audio_idx)
    {
        return 0;
    }
    AVStream* in_stream = input_fmt_ctx->streams[audio_idx];
    return static_cast<double>(in_stream->duration) * av_q2d(in_stream->time_base);
    */
    if (nullptr == input_fmt_ctx)
    {
        return 0;
    }
    return static_cast<double>(input_fmt_ctx->duration) / AV_TIME_BASE;
}

int Media::write_packet(void* opaque, uint8_t* buf, int buf_size)
{
    return ((Media*)opaque)->write_packet(buf, buf_size);
}

int Media::write_packet(uint8_t* buf, int buf_size)
{
    {
        // 写入队列操作
        std::unique_lock<std::mutex> lock(session.data_queues.mutex);
        session.data_queues.ts_packets.push(std::move(std::vector<uint8_t>(buf, buf + buf_size)));
        session.data_queues.cv.notify_all();
        session.run_status.queue_count = session.data_queues.ts_packets.size();
    }

    if (buf_size == DEC_BUFF_SIZE)
    {
        //缓冲区满，应该增加缓冲区
        //std::cout << "write_queue buf_size full" << std::endl;
    }

    session.run_status.dec_packages_count++;
    session.run_status.dec_packages_size += buf_size;

    //debugFile.write(reinterpret_cast<const char*>(buf), buf_size);
    //debugFile.flush();

    //出错返回 -1
    return buf_size;
}

bool Media::VideoDecodeThread(Media* This) {
    return This->VideoDecodeCallback();
}
bool Media::VideoDecodeCallback() {
    //视频解码编码，时间同步
    AVFrame* frame = av_frame_alloc();
    if (nullptr == frame)
    {
        std::cout << "VideoDecodeCallback av_frame_alloc fail" << std::endl;
        return false;
    }
    while (!stop_flag)
    {
        //检查队列，如果队列数据太多，暂停解码
        {
            std::unique_lock<std::mutex> lock(video_frame_queue_mutex);
            if (video_frame_queue.size() >= MAX_QUEUE_SIZE)
            {
                lock.unlock();
                video_frame_queue_cv.notify_all();
                std::this_thread::sleep_for(std::chrono::milliseconds(static_cast<int>(10)));
                continue;
            }
        }
        //音视频同步
        {
            std::unique_lock<std::mutex> lock(clock_mutex);
            if (audio_clock < video_clock && (video_clock - audio_clock) > 10 && (video_clock - audio_clock) < 60)
            {
                lock.unlock();
                audio_packet_queue_cv.notify_all();
                std::this_thread::sleep_for(std::chrono::milliseconds(static_cast<int>(1)));
                continue;
            }
        }
        std::unique_lock<std::mutex> lock(video_packet_queue_mutex);
        session.run_status.video_packet_count = video_packet_queue.size();
        video_packet_queue_cv.wait(lock, [this] { return!video_packet_queue.empty() || stop_flag; });
        if (stop_flag) {
            break;
        }
        AVPacket* packet = video_packet_queue.front();
        video_packet_queue.pop();
        lock.unlock();
        if (nullptr == packet)
        {
            //不应该出现这种情况
            std::cout << "VideoDecodeCallback packet null" << std::endl;
            continue;
        }


        if (avcodec_send_packet(video_dec_ctx, packet) != 0)
        {
            std::cout << "VideoDecodeCallback avcodec_send_packet fail" << std::endl;
        }
        while (avcodec_receive_frame(video_dec_ctx, frame) == 0) {
            if (stop_flag)
            {
                break;
            }
            if (video_start_time == AV_NOPTS_VALUE) {
                video_start_time = frame->pts;
            }
            if (video_start_time != AV_NOPTS_VALUE && frame->pts != AV_NOPTS_VALUE)
            {
                frame->pts -= video_start_time;
            }
            if (frame->pts != AV_NOPTS_VALUE)
            {
                std::lock_guard<std::mutex> lock(clock_mutex);
                video_clock = av_q2d(input_fmt_ctx->streams[video_idx]->time_base) * frame->pts;
            }
            else {
                double frame_delay = av_q2d(input_fmt_ctx->streams[video_idx]->time_base);
                frame_delay += frame->repeat_pict * (frame_delay * 0.5);
                std::lock_guard<std::mutex> lock(clock_mutex);
                video_clock += frame_delay;
            }
            // 处理硬件帧（可能需要传输到CPU）
            //AVFrame* sw_frame = av_frame_alloc();
            //av_hwframe_transfer_data(sw_frame, frame, 0);

            AVFrame* queue_frame = av_frame_clone(frame);
            if (nullptr == queue_frame)
            {
                std::cout << "VideoDecodeCallback av_frame_clone fail" << std::endl;
                break;
            }
            {
                std::lock_guard<std::mutex> lock(video_frame_queue_mutex);
                video_frame_queue.push(queue_frame);
            }
            video_frame_queue_cv.notify_all();
            av_frame_unref(frame);
            //av_frame_unref(sw_frame);
        }
        av_packet_free(&packet);
    }
    av_frame_free(&frame);
    return true;
}
bool Media::VideoEncodeCallback()
{
    int ret;
    AVPacket* enc_pkt = av_packet_alloc();
    if (nullptr == enc_pkt)
    {
        std::cout << "VideoEncodeCallback av_packet_alloc fail" << std::endl;
        return false;
    }
    AVFrame* enc_frame = av_frame_alloc();
    if (nullptr == enc_frame)
    {
        std::cout << "VideoEncodeCallback av_frame_alloc fail" << std::endl;
        av_packet_free(&enc_pkt);
        return false;
    }
    // 转换帧格式
    enc_frame->format = video_enc_ctx->pix_fmt;
    enc_frame->width = video_enc_ctx->width;
    enc_frame->height = video_enc_ctx->height;
    if (0 != av_frame_get_buffer(enc_frame, 0))
    {
        std::cout << "VideoEncodeCallback av_frame_get_buffer fail" << std::endl;
        av_frame_free(&enc_frame);
        av_packet_free(&enc_pkt);
        return false;
    }
    // 从编码器的硬件上下文中分配 GPU 内存
    /*
    if (av_hwframe_get_buffer(video_enc_ctx->hw_frames_ctx, enc_frame, 0) < 0) {
        std::cout << "VideoEncodeCallback av_hwframe_get_buffer fail" << std::endl;
        av_frame_free(&enc_frame);
        av_packet_free(&enc_pkt);
        return false;
    }
    */
    while (!stop_flag)
    {
        //检查队列，如果队列数据太多，暂停解码
        {
            std::unique_lock<std::mutex> lock(session.data_queues.mutex);
            if (session.data_queues.ts_packets.size() >= MAX_QUEUE_SIZE)
            {
                lock.unlock();
                session.data_queues.cv.notify_all();
                std::this_thread::sleep_for(std::chrono::milliseconds(static_cast<int>(10)));
                continue;
            }
        }
        std::unique_lock<std::mutex> lock(video_frame_queue_mutex);
        session.run_status.video_frame_count = video_frame_queue.size();
        video_frame_queue_cv.wait(lock, [this] { return!video_frame_queue.empty() || stop_flag; });
        if (stop_flag) {
            break;
        }
        AVFrame* frame = video_frame_queue.front();
        video_frame_queue.pop();
        lock.unlock();
        if (nullptr == frame)
        {
            //不应该出现这种情况
            std::cout << "VideoEncodeCallback frame null" << std::endl;
            av_frame_free(&frame);
            break;
        }

        sws_scale(sws_ctx, frame->data, frame->linesize, 0, frame->height, enc_frame->data, enc_frame->linesize);
        // 或直接引用原帧（避免拷贝）
        /*
        if (0 != av_frame_ref(enc_frame, frame))
        {
            std::cout << "VideoEncodeCallback av_frame_ref fail" << std::endl;
            av_frame_free(&frame);
            break;
        }
        */
        int64_t pts = frame->pts;

        enc_frame->pts = av_rescale_q(pts, input_fmt_ctx->streams[video_idx]->time_base, video_enc_ctx->time_base);
        // 编码并发送
        if (0 != (ret = avcodec_send_frame(video_enc_ctx, enc_frame)))
        {
            char errbuf[AV_ERROR_MAX_STRING_SIZE];
            av_strerror(ret, errbuf, AV_ERROR_MAX_STRING_SIZE);
            std::cout << "VideoEncodeCallback avcodec_send_frame fail：" << errbuf << std::endl;
            av_frame_free(&frame);
            break;
        }
        while (avcodec_receive_packet(video_enc_ctx, enc_pkt) == 0) {
            if (stop_flag)
            {
                break;
            }
            av_packet_rescale_ts(enc_pkt, video_enc_ctx->time_base, output_ctx->streams[0]->time_base);
            //enc_pkt->dts = enc_pkt->pts;
            enc_pkt->stream_index = 0;
            {
                std::unique_lock<std::mutex> interleaved_lock(_mutex);
                if (0 != av_interleaved_write_frame(output_ctx, enc_pkt))
                {
                    std::cout << "VideoEncodeCallback av_interleaved_write_frame fail" << std::endl;
                }
            }
            av_packet_unref(enc_pkt);
        }
        av_frame_free(&frame);
        //av_frame_unref(enc_frame);
    }
    av_packet_free(&enc_pkt);
    av_frame_free(&enc_frame);
    return true;
}
bool Media::AudioDecodeThread(Media* This) 
{
    return This->AudioDecodeCallback();
}
bool Media::AudioDecodeCallback() 
{
    AVFrame* frame = av_frame_alloc();
    if (nullptr == frame)
    {
        std::cout << "VideoDecodeCallback av_frame_alloc fail" << std::endl;
        return false;
    }
    while (!stop_flag)
    {
        //检查队列，如果队列数据太多，暂停解码
        {
            std::unique_lock<std::mutex> lock(audio_frame_queue_mutex);
            if (audio_frame_queue.size() >= MAX_QUEUE_SIZE)
            {
                lock.unlock();
                audio_frame_queue_cv.notify_all();
                std::this_thread::sleep_for(std::chrono::milliseconds(static_cast<int>(10)));
                continue;
            }
        }
        //音视频同步
        {
            std::unique_lock<std::mutex> lock(clock_mutex);
            if (audio_clock > video_clock && (audio_clock - video_clock) > 10 && (audio_clock - video_clock) < 60)
            {
                lock.unlock();
                video_packet_queue_cv.notify_all();
                std::this_thread::sleep_for(std::chrono::milliseconds(static_cast<int>(1)));
                continue;
            }
        }
        std::unique_lock<std::mutex> lock(audio_packet_queue_mutex);
        session.run_status.audio_packet_count = audio_packet_queue.size();
        audio_packet_queue_cv.wait(lock, [this] { return!audio_packet_queue.empty() || stop_flag; });
        if (stop_flag) {
            break;
        }
        AVPacket* packet = audio_packet_queue.front();
        audio_packet_queue.pop();
        lock.unlock();
        if (nullptr == packet)
        {
            //不应该出现这种情况
            std::cout << "AudioDecodeCallback packet null" << std::endl;
            continue;
        }
        if (audio_start_time == AV_NOPTS_VALUE) {
            audio_start_time = packet->pts;
        }
        packet->pts -= audio_start_time;

        if (packet->pts != AV_NOPTS_VALUE) {
            if (audio_last_pts == AV_NOPTS_VALUE)
            {
                audio_last_pts = packet->pts;
            }
            else
            {
                int64_t pts = audio_last_pts + audio_dec_ctx->frame_size;
                if (abs(packet->pts - pts) > 1)
                {
                    //fprintf(stderr, "PTS不连续! 前值:%lld 当前:%lld\n", pts, packet->pts);
                    //packet->pts = pts;
                }
            }
            std::lock_guard<std::mutex> lock(clock_mutex);
            audio_clock = av_q2d(input_fmt_ctx->streams[audio_idx]->time_base) * packet->pts;
        }
        else {
            if (audio_last_pts != AV_NOPTS_VALUE)
            {
                packet->pts = audio_last_pts + audio_dec_ctx->frame_size;
            }
            std::lock_guard<std::mutex> lock(clock_mutex);
            audio_clock = av_q2d(input_fmt_ctx->streams[audio_idx]->time_base) * packet->pts;
        }
        audio_last_pts = packet->pts;


        if (avcodec_send_packet(audio_dec_ctx, packet) != 0)
        {
            std::cout << "AudioDecodeCallback avcodec_send_packet fail" << std::endl;
        }
        bool first_frame = true;
        while (avcodec_receive_frame(audio_dec_ctx, frame) == 0) {
            if (stop_flag)
            {
                break;
            }
            if (audio_start_time == AV_NOPTS_VALUE) {
                audio_start_time = frame->pts;
            }
            if (audio_start_time != AV_NOPTS_VALUE && frame->pts != AV_NOPTS_VALUE)
            {
                //frame->pts -= audio_start_time;
            }
            if (frame->pts != AV_NOPTS_VALUE) {
                if (audio_last_pts == AV_NOPTS_VALUE)
                {
                    audio_last_pts = frame->pts;
                }
                else
                {
                    int64_t pts = audio_last_pts;
                    if (!first_frame) {
                        pts += audio_dec_ctx->frame_size;
                    }
                    if (abs(frame->pts - pts) > 1)
                    {
                        //fprintf(stderr, "PTS不连续! 前值:%lld 当前:%lld\n", pts, frame->pts);
                        frame->pts = pts;
                    }
                }
                std::lock_guard<std::mutex> lock(clock_mutex);
                audio_clock = av_q2d(input_fmt_ctx->streams[audio_idx]->time_base) * frame->pts;
            }
            else {
                if (audio_last_pts != AV_NOPTS_VALUE)
                {
                    frame->pts = audio_last_pts + audio_dec_ctx->frame_size;
                }
                std::lock_guard<std::mutex> lock(clock_mutex);
                audio_clock = av_q2d(input_fmt_ctx->streams[audio_idx]->time_base) * frame->pts;
            }
            audio_last_pts = frame->pts;
            first_frame = false;

            AVFrame* queue_frame = av_frame_clone(frame);
            if (nullptr == queue_frame)
            {
                std::cout << "AudioDecodeCallback av_frame_clone fail" << std::endl;
                break;
            }
            {
                std::lock_guard<std::mutex> lock(audio_frame_queue_mutex);
                audio_frame_queue.push(queue_frame);
            }
            audio_frame_queue_cv.notify_all();
            av_frame_unref(frame);
        }

        av_packet_free(&packet);
    }
    av_frame_free(&frame);
    return true;

}

bool Media::AudioEncodeCallback()
{
    AVPacket* enc_pkt = av_packet_alloc();
    if (nullptr == enc_pkt)
    {
        std::cout << "AudioEncodeCallback av_packet_alloc fail" << std::endl;
        return false;
    }
    AVFrame* enc_frame = av_frame_alloc();
    if (nullptr == enc_frame)
    {
        std::cout << "AudioEncodeCallback av_frame_alloc fail" << std::endl;
        av_packet_free(&enc_pkt);
        return false;
    }
    // 使用新API设置音频帧参数
    enc_frame->sample_rate = audio_enc_ctx->sample_rate;
    av_channel_layout_copy(&enc_frame->ch_layout, &audio_enc_ctx->ch_layout);
    enc_frame->format = audio_enc_ctx->sample_fmt;
    enc_frame->nb_samples = audio_enc_ctx->frame_size;
    if (0 != av_frame_get_buffer(enc_frame, 0))
    {
        std::cout << "AudioEncodeCallback av_frame_get_buffer fail" << std::endl;
        av_frame_free(&enc_frame);
        av_packet_free(&enc_pkt);
        return false;
    }
    while (!stop_flag)
    {
        //检查队列，如果队列数据太多，暂停解码
        {
            std::unique_lock<std::mutex> lock(session.data_queues.mutex);
            session.run_status.audio_frame_count = audio_frame_queue.size();
            if (session.data_queues.ts_packets.size() >= MAX_QUEUE_SIZE)
            {
                lock.unlock();
                session.data_queues.cv.notify_all();
                std::this_thread::sleep_for(std::chrono::milliseconds(static_cast<int>(10)));
                continue;
            }
        }
        std::unique_lock<std::mutex> lock(audio_frame_queue_mutex);
        audio_frame_queue_cv.wait(lock, [this] { return!audio_frame_queue.empty() || stop_flag; });
        if (stop_flag) {
            break;
        }
        AVFrame* frame = audio_frame_queue.front();
        audio_frame_queue.pop();
        lock.unlock();
        if (nullptr == frame)
        {
            //不应该出现这种情况
            std::cout << "AudioEncodeCallback frame null" << std::endl;
        }

        // 执行重采样
        if (0 != swr_convert_frame(swr_ctx, enc_frame, frame))
        {
            std::cout << "AudioEncodeCallback swr_convert_frame fail" << std::endl;
        }
        if (0 != avcodec_send_frame(audio_enc_ctx, enc_frame))
        {
            std::cout << "AudioEncodeCallback avcodec_send_frame fail" << std::endl;
            av_frame_free(&frame);
            break;
        }
        while (avcodec_receive_packet(audio_enc_ctx, enc_pkt) == 0) {
            if (stop_flag)
            {
                break;
            }
            // 如果已设置 audio_output_stream->time_base = audio_encoder_context->time_base
            // 则无需转换，否则需正确匹配
            //av_packet_rescale_ts(enc_pkt, audio_enc_ctx->time_base, output_ctx->streams[1]->time_base);
            /*
            static int64_t last_pts = AV_NOPTS_VALUE;
            if (last_pts != AV_NOPTS_VALUE && enc_pkt->pts != last_pts + 1920) {
                fprintf(stderr, "PTS不连续! 前值:%lld 当前:%lld\n",
                    last_pts, enc_pkt->pts);
            }
            last_pts = enc_pkt->pts;
            */

            // 强制DTS同步
            //enc_pkt->dts = enc_pkt->pts;
            enc_pkt->stream_index = 1;
            {
                std::unique_lock<std::mutex> interleaved_lock(_mutex);
                if (0 != av_interleaved_write_frame(output_ctx, enc_pkt))
                {
                    std::cout << "AudioEncodeCallback av_interleaved_write_frame fail" << std::endl;
                }
            }
            av_packet_unref(enc_pkt);
        }
        av_frame_free(&frame);
    }
    av_packet_free(&enc_pkt);
    av_frame_free(&enc_frame);
    return true;
}

bool Media::VideoEncodeThread(Media* This)
{
    return This->VideoEncodeCallback();
}

bool Media::AudioEncodeThread(Media* This)
{
    return This->AudioEncodeCallback();
}


void Media::ClearQueue()
{
    {
        std::lock_guard<std::mutex> lock(video_packet_queue_mutex);
        while (!video_packet_queue.empty())
        {
            AVPacket* packet = video_packet_queue.front();
            video_packet_queue.pop();
            if (nullptr != packet) {
                av_packet_free(&packet);
            }
        }
    }
    {
        std::lock_guard<std::mutex> lock(audio_packet_queue_mutex);
        while (!audio_packet_queue.empty())
        {
            AVPacket* packet = audio_packet_queue.front();
            audio_packet_queue.pop();
            if (nullptr != packet) {
                av_packet_free(&packet);
            }
        }
    }
    {
        std::lock_guard<std::mutex> lock(video_frame_queue_mutex);
        while (!video_frame_queue.empty())
        {
            AVFrame* frame = video_frame_queue.front();
            video_frame_queue.pop();
            if (nullptr != frame) {
                av_frame_free(&frame);
            }
        }
    }
    {
        std::lock_guard<std::mutex> lock(audio_frame_queue_mutex);
        while (!audio_frame_queue.empty())
        {
            AVFrame* frame = audio_frame_queue.front();
            audio_frame_queue.pop();
            if (nullptr != frame) {
                av_frame_free(&frame);
            }
        }
    }
    /*
    {
        std::lock_guard<std::mutex> lock(session.data_queues.mutex);
        while (!session.data_queues.ts_packets.empty()) {
            session.data_queues.ts_packets.pop();
        }
    }
    */
}

bool Media::MainThread(Media* This)
{
    return This->MainCallback();
}

bool Media::MainRawThread(Media* This)
{
    return This->MainRawCallback();
}

bool Media::MainRawCallback()
{
    if (nullptr == input_fmt_ctx)
    {
        return false;
    }
    if (!stop_flag) {
        return true;
    }
    // 准备输出上下文
    if (avformat_alloc_output_context2(&output_ctx, NULL, "mpegts", NULL) < 0)
    {
        std::cout << "avformat_alloc_output_context2 fail" << std::endl;
        return false;
    }
    avio_ctx = avio_alloc_context((unsigned char*)av_malloc(DEC_BUFF_SIZE), DEC_BUFF_SIZE, 1, this, NULL, Media::write_packet, NULL);
    if (nullptr == avio_ctx) {
        std::cout << "avio_alloc_context fail" << std::endl;
        Stop();
        return false;
    }
    output_ctx->pb = avio_ctx;

    AVStream* streams[20];
    memset(streams, 0, sizeof(streams));

    //直接复制所有流
    for (unsigned int i = 0; i < input_fmt_ctx->nb_streams && i < 20; i++) {
        AVStream* in_stream = input_fmt_ctx->streams[i];
        AVStream* out_stream = avformat_new_stream(output_ctx, nullptr);
        if (!out_stream) {
            std::cerr << "Failed allocating output stream:" << i << std::endl;
            //return false;
            continue;
        }
        streams[i] = out_stream;
        if (avcodec_parameters_copy(out_stream->codecpar, in_stream->codecpar) < 0) {
            std::cerr << "Failed to copy codec parameters:" << i << std::endl;
            //return false;
            continue;
        }
        out_stream->time_base = in_stream->time_base;
    }
    // 写入输出头部
    avformat_write_header(output_ctx, NULL);

    AVPacket* encoded_packet = av_packet_alloc();
    if (nullptr == encoded_packet)
    {
        std::cout << "av_packet_alloc fail" << std::endl;
        Stop();
        return false;
    }
    stop_flag = false;

    av_seek_frame(input_fmt_ctx, -1, seek_target_, AVSEEK_FLAG_BACKWARD);

    while (!stop_flag) {
        session.run_status.audio_clock = audio_clock;
        session.run_status.video_clock = video_clock;
        bool pause = false;
        //检查队列，如果队列数据太多，暂停解码
        {
            std::lock_guard<std::mutex> lock(session.data_queues.mutex);
            if (session.data_queues.ts_packets.size() >= MAX_QUEUE_SIZE)
            {
                session.data_queues.cv.notify_all();
                pause = true;
            }
        }
        if (pause)
        {
            std::this_thread::sleep_for(std::chrono::milliseconds(static_cast<int>(1)));
            continue;
        }

        int ret = av_read_frame(input_fmt_ctx, encoded_packet);
        if (ret != 0) {
            if (ret == AVERROR_EOF)
            {
                break;
            }
            else
            {
                // 处理文件结束或错误
                std::cout << "av_read_frame fail" << std::endl;
            }
        }

        //不转码直接输出
        AVStream* in_stream = input_fmt_ctx->streams[encoded_packet->stream_index];
        AVStream* out_stream = streams[encoded_packet->stream_index];
        if (nullptr != out_stream)
        {
            encoded_packet->pts = av_rescale_q_rnd(encoded_packet->pts, in_stream->time_base, out_stream->time_base,
                static_cast<AVRounding>(AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX));
            encoded_packet->dts = av_rescale_q_rnd(encoded_packet->dts, in_stream->time_base, out_stream->time_base,
                static_cast<AVRounding>(AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX));
            encoded_packet->duration = av_rescale_q(encoded_packet->duration, in_stream->time_base, out_stream->time_base);
            encoded_packet->pos = -1;

            if (av_interleaved_write_frame(output_ctx, encoded_packet) < 0) {
                std::cerr << "Error muxing packet" << std::endl;
                break;
            }
        }
        session.run_status.read_packet_count++;
        av_packet_unref(encoded_packet);
        if ((session.run_status.read_packet_count % 3) == 0)
        {
            std::this_thread::sleep_for(std::chrono::milliseconds(static_cast<int>(1)));
        }
    }
    // 收尾工作
    av_write_trailer(output_ctx);
    av_packet_free(&encoded_packet);
    return true;
}

bool Media::MainCallback()
{
    if (nullptr == input_fmt_ctx)
    {
        return false;
    }
    if (!stop_flag) {
        return true;
    }
    // 准备输出上下文
    if (avformat_alloc_output_context2(&output_ctx, NULL, "mpegts", NULL) < 0)
    {
        std::cout << "avformat_alloc_output_context2 fail" << std::endl;
        return false;
    }
    avio_ctx = avio_alloc_context((unsigned char*)av_malloc(DEC_BUFF_SIZE), DEC_BUFF_SIZE, 1, this, NULL, Media::write_packet, NULL);
    if (nullptr == avio_ctx) {
        std::cout << "avio_alloc_context fail" << std::endl;
        Stop();
        return false;
    }
    output_ctx->pb = avio_ctx;


    // 配置视频流
    if (video_idx >= 0) {
        AVStream* in_stream = input_fmt_ctx->streams[video_idx];

        const AVCodec* dec = avcodec_find_decoder(in_stream->codecpar->codec_id);
        //硬件解码
        //const AVCodec* dec = avcodec_find_decoder_by_name("h264_cuvid");
        if (nullptr == dec) {
            std::cout << "avcodec_find_decoder fail" << std::endl;
            Stop();
            return false;
        }
        video_dec_ctx = avcodec_alloc_context3(dec);
        if (nullptr == video_dec_ctx) {
            std::cout << "avcodec_alloc_context3 fail" << std::endl;
            Stop();
            return false;
        }
        if (avcodec_parameters_to_context(video_dec_ctx, in_stream->codecpar) < 0)
        {
            std::cout << "avcodec_parameters_to_context fail" << std::endl;
            Stop();
            return false;
        }
        // 设置硬件设备
        /*
        {
            AVBufferRef* hw_device_ctx;
            if (0 != av_hwdevice_ctx_create(&hw_device_ctx, AV_HWDEVICE_TYPE_CUDA, NULL, NULL, 0))
            {
                std::cout << "av_hwdevice_ctx_create fail" << std::endl;
                Stop();
                return false;
            }
            video_dec_ctx->hw_device_ctx = av_buffer_ref(hw_device_ctx);
        }
        */
        if (0 != avcodec_open2(video_dec_ctx, dec, NULL))
        {
            std::cout << "avcodec_open2 fail" << std::endl;
            Stop();
            return false;
        }

        //const AVCodec* enc = avcodec_find_encoder(AV_CODEC_ID_H264);
        //const AVCodec* enc = avcodec_find_encoder_by_name("h264_nvenc");
        const AVCodec* enc = avcodec_find_encoder_by_name("libx264");
        if (nullptr == enc) {
            std::cout << "avcodec_find_encoder fail" << std::endl;
            Stop();
            return false;
        }
        video_enc_ctx = avcodec_alloc_context3(enc);
        if (nullptr == video_enc_ctx) {
            std::cout << "avcodec_alloc_context3 fail" << std::endl;
            Stop();
            return false;
        }
        video_enc_ctx->height = video_dec_ctx->height;
        video_enc_ctx->width = video_dec_ctx->width;
        video_enc_ctx->sample_aspect_ratio = video_dec_ctx->sample_aspect_ratio;


        //video_enc_ctx->pix_fmt = avcodec_find_best_pix_fmt_of_list(enc->pix_fmts, video_dec_ctx->pix_fmt, 0, NULL);
        video_enc_ctx->pix_fmt = AV_PIX_FMT_YUV420P;
        //video_enc_ctx->pix_fmt = AV_PIX_FMT_CUDA;
        video_enc_ctx->time_base = TS_TIME_BASE;// MPEG-TS标准时间基
        //video_enc_ctx->framerate = { 25, 1 }; // 明确设置帧率
        video_enc_ctx->framerate = video_dec_ctx->framerate;
        video_enc_ctx->bit_rate = 1000000 * 3; //3 * 1M 比特率

        AVDictionary* opts = nullptr;
        av_dict_set(&opts, "preset", "medium", 0);
        av_dict_set(&opts, "tune", "fastdecode", 0);
        av_dict_set_int(&opts, "crf", 23, 0);
        av_dict_set(&opts, "maxrate", "2500k", 0);
        av_dict_set(&opts, "bufsize", "6000k", 0);

        // 设置硬件设备
        /*
        {
            // 创建硬件设备上下文
            AVBufferRef* hw_device_ctx;
            if (av_hwdevice_ctx_create(&hw_device_ctx, AV_HWDEVICE_TYPE_CUDA, NULL, NULL, 0) < 0) {
                std::cout << "av_hwdevice_ctx_create fail" << std::endl;
                Stop();
                return false;
            }

            // 创建硬件帧上下文（解决格式不匹配问题）
            AVBufferRef* hw_frames_ctx = av_hwframe_ctx_alloc(hw_device_ctx);
            if (!hw_frames_ctx) {
                std::cout << "av_hwframe_ctx_alloc fail" << std::endl;
                Stop();
                return false;
            }

            AVHWFramesContext* frames_ctx = (AVHWFramesContext*)hw_frames_ctx->data;
            frames_ctx->format = AV_PIX_FMT_CUDA;          // 必须与 enc_ctx->pix_fmt 一致
            frames_ctx->sw_format = AV_PIX_FMT_NV12;       // GPU 高效格式
            frames_ctx->width = video_dec_ctx->width;
            frames_ctx->height = video_dec_ctx->height;
            frames_ctx->initial_pool_size = 10;            // 预分配帧缓冲区数量

            if (av_hwframe_ctx_init(hw_frames_ctx) < 0) {
                av_buffer_unref(&hw_frames_ctx);
                std::cout << "av_hwframe_ctx_init fail" << std::endl;
                Stop();
                return false;
            }

            // 绑定硬件帧上下文到编码器
            video_enc_ctx->hw_frames_ctx = av_buffer_ref(hw_frames_ctx);
            if (!video_enc_ctx->hw_frames_ctx) {
                av_buffer_unref(&hw_frames_ctx);
                std::cout << "av_hwframe_ctx_init fail" << std::endl;
                Stop();
                return false;
            }
        }
        */

        // 视频编码器参数
        //av_opt_set(video_enc_ctx->priv_data, "sync-lookahead", "0", 0);
        //av_opt_set(video_enc_ctx->priv_data, "rc-lookahead", "0", 0);
        //tune影响解码编码质量，如果发现编码速度慢，卡顿，就设置为zerolatency
        av_opt_set(video_enc_ctx->priv_data, "tune", "fastdecode", 0); //film/animation/grain/stillimage/psnr 和 ssim/fastdecode/zerolatency
        //preset影响解码编码速度，如ultrafast可能会丢帧，如果发现出现丢帧，就把速度调慢
        av_opt_set(video_enc_ctx->priv_data, "preset", "fast", 0); //ultrafast/superfast/veryfast/faster/fast/medium/slow/slower/veryslow
        //av_opt_set(video_enc_ctx->priv_data, "crf", "23", 0);
        //av_opt_set(video_enc_ctx->priv_data, "bufsize", "2M", 0);
        //av_opt_set(video_enc_ctx->priv_data, "maxrate", "2M", 0);
        //av_opt_set(video_enc_ctx->priv_data, "f", "mpegts", 0);
        //av_opt_set(video_enc_ctx->priv_data, "async", "1", 0);
        //av_opt_set(video_enc_ctx->priv_data, "vsync", "vfr", 0);
        //av_opt_set(video_enc_ctx->priv_data, "af", "aresample=async=1:min_hard_comp=0.100000:first_pts=0", 0);
        //av_opt_set(video_enc_ctx->priv_data, "fflags", "+genpts", 0);
        //av_opt_set(video_enc_ctx->priv_data, "copyts", "", 0);
        //av_opt_set(video_enc_ctx->priv_data, "muxdelay", "0", 0);

        //下面是cuda设置
        //av_opt_set(video_enc_ctx->priv_data, "preset", "llhp", 0);      // 低延迟高质量预设
        //av_opt_set(video_enc_ctx->priv_data, "tune", "ull", 0);        // 超低延迟模式
        //av_opt_set(video_enc_ctx->priv_data, "rc", "vbr", 0);    // 码率模式

        //NVENC 参数	有效值	说明
        //preset	llhp, hq, bd, lossless	编码质量 / 速度预设
        //tune	ull, hq, ll	延迟优化方向
        //rc(码率控制)	cbr, vbr, cbr_ld_hq	码率控制模式


        video_enc_ctx->max_b_frames = 0;
        video_enc_ctx->gop_size = 10;

        if (0 != avcodec_open2(video_enc_ctx, enc, &opts))
        {
            std::cout << "avcodec_open2 fail" << std::endl;
            Stop();
            return false;
        }

        AVStream* out_stream = avformat_new_stream(output_ctx, enc);
        if (nullptr == out_stream)
        {
            std::cout << "avformat_new_stream fail" << std::endl;
            Stop();
            return false;
        }
        if (avcodec_parameters_from_context(out_stream->codecpar, video_enc_ctx) < 0)
        {
            std::cout << "avcodec_parameters_from_context fail" << std::endl;
            Stop();
            return false;
        }
        out_stream->time_base = video_enc_ctx->time_base;

        sws_ctx = sws_getContext(
            video_dec_ctx->width, video_dec_ctx->height, video_dec_ctx->pix_fmt,
            video_enc_ctx->width, video_enc_ctx->height, video_enc_ctx->pix_fmt,
            SWS_BILINEAR, NULL, NULL, NULL);
        if (nullptr == sws_ctx)
        {
            std::cout << "sws_getContext fail" << std::endl;
            Stop();
            return false;
        }

    }
    // 配置音频流（类似视频，需处理重采样）
    if (audio_idx >= 0) {
        AVStream* in_stream = input_fmt_ctx->streams[audio_idx];
        const AVCodec* dec = avcodec_find_decoder(in_stream->codecpar->codec_id);
        if (nullptr == dec)
        {
            std::cout << "avcodec_find_decoder fail" << std::endl;
            Stop();
            return false;
        }
        audio_dec_ctx = avcodec_alloc_context3(dec);
        if (nullptr == audio_dec_ctx)
        {
            std::cout << "avcodec_alloc_context3 fail" << std::endl;
            Stop();
            return false;
        }
        if (avcodec_parameters_to_context(audio_dec_ctx, in_stream->codecpar) < 0)
        {
            std::cout << "avcodec_parameters_to_context fail" << std::endl;
            Stop();
            return false;
        }
        if (0 != avcodec_open2(audio_dec_ctx, dec, NULL))
        {
            std::cout << "avcodec_open2 fail" << std::endl;
            Stop();
            return false;
        }

        const AVCodec* enc = avcodec_find_encoder(AV_CODEC_ID_AAC);
        if (nullptr == enc)
        {
            std::cout << "avcodec_find_encoder fail" << std::endl;
            Stop();
            return false;
        }
        audio_enc_ctx = avcodec_alloc_context3(enc);
        if (nullptr == audio_enc_ctx)
        {
            std::cout << "avcodec_alloc_context3 fail" << std::endl;
            Stop();
            return false;
        }

        // 设置音频编码参数（新API方式）
        audio_enc_ctx->sample_rate = 48000;
        audio_enc_ctx->sample_fmt = enc->sample_fmts[0]; // 选择第一个支持的格式

        // 使用新的通道布局系统
        AVChannelLayout stereo_layout;
        av_channel_layout_default(&stereo_layout, 2); // 立体声
        //av_channel_layout_copy(&audio_enc_ctx->ch_layout, &stereo_layout);
        av_channel_layout_default(&audio_enc_ctx->ch_layout, 2); // 立体声

        //audio_enc_ctx->bit_rate = 128000;
        audio_enc_ctx->bit_rate = 160000;
        audio_enc_ctx->time_base = { 1, audio_enc_ctx->sample_rate };

        //av_opt_set(audio_enc_ctx->priv_data, "aac_coder", "fast", 0);
        //audio_enc_ctx->frame_size = 1024; // AAC每帧1024样本
        //audio_enc_ctx->has_b_frames = 0;

        if (avcodec_open2(audio_enc_ctx, enc, NULL) != 0) {
            std::cout << "avcodec_open2 fail" << std::endl;
            Stop();
            return false;
        }

        AVStream* out_stream = avformat_new_stream(output_ctx, enc);
        avcodec_parameters_from_context(out_stream->codecpar, audio_enc_ctx);
        out_stream->time_base = audio_enc_ctx->time_base;


        // 初始化音频重采样器（新API）
        swr_ctx = swr_alloc();
        if (nullptr == swr_ctx)
        {
            std::cout << "swr_alloc fail" << std::endl;
            Stop();
            return false;
        }
        if (0 != swr_alloc_set_opts2(&swr_ctx,
            &audio_enc_ctx->ch_layout, // 输出布局
            audio_enc_ctx->sample_fmt,
            audio_enc_ctx->sample_rate,
            &audio_dec_ctx->ch_layout, // 输入布局
            audio_dec_ctx->sample_fmt,
            audio_dec_ctx->sample_rate,
            0, NULL))
        {
            std::cout << "swr_alloc_set_opts2 fail" << std::endl;
            Stop();
            return false;
        }
        swr_init(swr_ctx);
    }
    // 写入输出头部
    avformat_write_header(output_ctx, NULL);

    AVPacket* encoded_packet = av_packet_alloc();
    if (nullptr == encoded_packet)
    {
        std::cout << "av_packet_alloc fail" << std::endl;
        Stop();
        return false;
    }
    stop_flag = false;
    //启动解码编码线程
    video_decode_thread = std::thread(Media::VideoDecodeThread, this);
    audio_decode_thread = std::thread(Media::AudioDecodeThread, this);
    video_encode_thread = std::thread(Media::VideoEncodeThread, this);
    audio_encode_thread = std::thread(Media::AudioEncodeThread, this);

    av_seek_frame(input_fmt_ctx, -1, seek_target_, AVSEEK_FLAG_BACKWARD);

    while (!stop_flag) {
        //处理seek请求
        if (seek_requested_)
        {
            av_seek_frame(input_fmt_ctx, 1, seek_target_, AVSEEK_FLAG_BACKWARD);
            stop_flag = true;
            std::this_thread::sleep_for(std::chrono::milliseconds(static_cast<int>(1)));
            //重启编码解码线程
            if (video_decode_thread.joinable())
            {
                video_packet_queue_cv.notify_all();
                video_decode_thread.join();
            }
            if (audio_decode_thread.joinable())
            {
                audio_packet_queue_cv.notify_all();
                audio_decode_thread.join();
            }
            if (video_encode_thread.joinable())
            {
                video_frame_queue_cv.notify_all();
                video_encode_thread.join();
            }
            if (audio_encode_thread.joinable())
            {
                audio_frame_queue_cv.notify_all();
                audio_encode_thread.join();
            }
            // 清空所有缓冲区
            avcodec_flush_buffers(video_dec_ctx);
            avcodec_flush_buffers(video_enc_ctx);
            avcodec_flush_buffers(audio_dec_ctx);
            avcodec_flush_buffers(audio_enc_ctx);
            ClearQueue();
            stop_flag = false;
            seek_requested_ = false;
            {
                std::lock_guard<std::mutex> lock(clock_mutex);
                video_clock = 0;
                audio_clock = 0;
            }
            video_start_time = AV_NOPTS_VALUE;
            audio_start_time = AV_NOPTS_VALUE;
            video_last_pts = AV_NOPTS_VALUE;
            audio_last_pts = AV_NOPTS_VALUE;
            video_decode_thread = std::thread(Media::VideoDecodeThread, this);
            audio_decode_thread = std::thread(Media::AudioDecodeThread, this);
            video_encode_thread = std::thread(Media::VideoEncodeThread, this);
            audio_encode_thread = std::thread(Media::AudioEncodeThread, this);
        }
        session.run_status.audio_clock = audio_clock;
        session.run_status.video_clock = video_clock;
        bool pause = false;
        //检查队列，如果队列数据太多，暂停解码
        {
            std::lock_guard<std::mutex> lock(video_packet_queue_mutex);
            if (video_packet_queue.size() >= MAX_QUEUE_SIZE)
            {
                video_packet_queue_cv.notify_all();
                pause = true;
            }
        }
        {
            std::lock_guard<std::mutex> lock(audio_packet_queue_mutex);
            if (audio_packet_queue.size() >= MAX_QUEUE_SIZE)
            {
                audio_packet_queue_cv.notify_all();
                pause = true;
            }
        }
        {
            std::lock_guard<std::mutex> lock(session.data_queues.mutex);
            if (session.data_queues.ts_packets.size() >= MAX_QUEUE_SIZE)
            {
                session.data_queues.cv.notify_all();
                pause = true;
            }
        }
        if (pause)
        {
            std::this_thread::sleep_for(std::chrono::milliseconds(static_cast<int>(1)));
            continue;
        }

        int ret = av_read_frame(input_fmt_ctx, encoded_packet);
        if (ret != 0) {
            if (ret == AVERROR_EOF)
            {
                break;
            }
            else
            {
                // 处理文件结束或错误
                std::cout << "av_read_frame fail" << std::endl;
            }
        }

        if (encoded_packet->stream_index == video_idx) {
            AVPacket* queue_packet = av_packet_clone(encoded_packet);
            if (nullptr == queue_packet)
            {
                std::cout << "av_packet_clone fail" << std::endl;
                Stop();
                break;
            }
            {
                std::lock_guard<std::mutex> lock(video_packet_queue_mutex);
                video_packet_queue.push(queue_packet);
            }
            video_packet_queue_cv.notify_all();
        }
        else if (encoded_packet->stream_index == audio_idx) {
            AVPacket* queue_packet = av_packet_clone(encoded_packet);
            if (nullptr == queue_packet)
            {
                std::cout << "av_packet_clone fail" << std::endl;
                Stop();
                break;
            }
            {
                std::lock_guard<std::mutex> lock(audio_packet_queue_mutex);
                audio_packet_queue.push(queue_packet);
            }
            audio_packet_queue_cv.notify_all();
        }
        session.run_status.read_packet_count++;
        av_packet_unref(encoded_packet);
        if ((session.run_status.read_packet_count % 3) == 0)
        {
            std::this_thread::sleep_for(std::chrono::milliseconds(static_cast<int>(1)));
        }
    }
    // 收尾工作
    av_write_trailer(output_ctx);
    //等待编码解码线程结束
    if (video_decode_thread.joinable())
    {
        video_decode_thread.join();
    }
    if (audio_decode_thread.joinable())
    {
        audio_decode_thread.join();
    }
    if (video_encode_thread.joinable())
    {
        video_encode_thread.join();
    }
    if (audio_encode_thread.joinable())
    {
        audio_encode_thread.join();
    }
    av_packet_free(&encoded_packet);
    ClearQueue();
    return true;
}

bool Media::MainPipeThread(Media* This)
{
    return This->MainPipeCallback();
}

bool Media::MainPipeCallback()
{
    std::string pathgb2312 = UTF8ToGB2312(path_file);

    // 构建 ffmpeg 命令
    //std::string ffmpegCommand = "ffmpeg -loglevel quiet -ss " + std::to_string((uint32_t)seek_target_ / 1000000) + " -i \"" + pathgb2312 + "\" -c:v libx264 -preset faster -tune fastdecode -maxrate 1.5M -b:v 1.5M -c:a aac -b:a 160k -f mpegts -flush_packets 0 -mpegts_flags resend_headers pipe:1";
    std::string ffmpegCommand = "ffmpeg -loglevel quiet -ss " + std::to_string((uint32_t)seek_target_ / 1000000) + " -i \"" + pathgb2312 + "\" -c copy -f mpegts -flush_packets 0 -mpegts_flags resend_headers pipe:1";

    std::cout << ffmpegCommand << std::endl;

    SECURITY_ATTRIBUTES sa = { sizeof(SECURITY_ATTRIBUTES), NULL, TRUE };
    HANDLE hReadPipe, hWritePipe;

    // 创建数据管道
    if (!CreatePipe(&hReadPipe, &hWritePipe, &sa, 0)) {
        std::cerr << "CreatePipe failed (" << GetLastError() << ")\n";
        return 1;
    }

    // 创建NUL设备句柄用于重定向stderr
    HANDLE hNull = CreateFile(
        "NUL",
        GENERIC_WRITE,
        FILE_SHARE_WRITE,
        &sa,
        OPEN_EXISTING,
        0,
        NULL
    );
    if (hNull == INVALID_HANDLE_VALUE) {
        std::cerr << "CreateFile(NUL) failed (" << GetLastError() << ")\n";
        CloseHandle(hReadPipe);
        CloseHandle(hWritePipe);
        return 1;
    }

    // 配置进程启动参数
    STARTUPINFO si = { sizeof(STARTUPINFO) };
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdOutput = hWritePipe;    // 媒体数据输出到管道
    si.hStdError = hNull;          // 日志输出到NUL设备
    si.hStdInput = GetStdHandle(STD_INPUT_HANDLE);

    PROCESS_INFORMATION pi;

    // 创建FFmpeg进程
    if (!CreateProcess(
        NULL,
        const_cast<LPSTR>(ffmpegCommand.c_str()),
        NULL,
        NULL,
        TRUE,    // 继承句柄
        CREATE_NO_WINDOW,  // 不显示控制台窗口
        NULL,
        NULL,
        &si,
        &pi
    )) {
        std::cerr << "CreateProcess failed (" << GetLastError() << ")\n";
        CloseHandle(hReadPipe);
        CloseHandle(hWritePipe);
        CloseHandle(hNull);
        return 1;
    }

    // 关闭不再使用的句柄
    CloseHandle(hWritePipe);
    CloseHandle(hNull);
    CloseHandle(pi.hThread);


    BYTE buffer[4096];
    DWORD bytesRead;
    std::vector<BYTE> tempBuffer;  // 临时缓冲
    stop_flag = false;
    while (!stop_flag) {
        bool pause = false;
        //检查队列，如果队列数据太多，暂停解码
        {
            std::lock_guard<std::mutex> lock(session.data_queues.mutex);
            if (session.data_queues.ts_packets.size() >= MAX_QUEUE_SIZE)
            {
                session.data_queues.cv.notify_all();
                pause = true;
            }
        }
        if (pause)
        {
            std::this_thread::sleep_for(std::chrono::milliseconds(static_cast<int>(1)));
            continue;
        }
        if (!ReadFile(hReadPipe, buffer, sizeof(buffer), &bytesRead, NULL))
        {
            DWORD errorCode = GetLastError();
            std::cerr << "ReadFile failed with error code: " << errorCode << std::endl;
            switch (errorCode) {
            case ERROR_INVALID_HANDLE:
                std::cerr << "Invalid handle." << std::endl;
                break;
            case ERROR_HANDLE_EOF:
                std::cerr << "End of file or pipe reached." << std::endl;
                break;
            case ERROR_NOT_ENOUGH_MEMORY:
                std::cerr << "Not enough memory." << std::endl;
                break;
            default:
                std::cerr << "Unknown error." << std::endl;
                break;
            }
            break;
        }
        if (bytesRead > 0) {
            // 将新数据追加到临时缓冲
            tempBuffer.insert(tempBuffer.end(), buffer, buffer + bytesRead);
            // 分割完整TS包
            size_t packetCount = tempBuffer.size() / TS_PACKET_SIZE;
            for (size_t i = 0; i < packetCount; ++i) {
                auto start = tempBuffer.begin() + i * TS_PACKET_SIZE;
                auto end = start + TS_PACKET_SIZE;
                {
                    // 写入队列操作
                    std::unique_lock<std::mutex> lock(session.data_queues.mutex);
                    session.data_queues.ts_packets.push(std::move(std::vector<uint8_t>(start, end)));
                    session.run_status.queue_count = session.data_queues.ts_packets.size();
                }
                session.data_queues.cv.notify_all();
            }

            // 保留不完整的数据
            size_t remaining = tempBuffer.size() % TS_PACKET_SIZE;
            tempBuffer.assign(
                tempBuffer.end() - remaining,
                tempBuffer.end()
            );
            session.run_status.dec_packages_count++;
            session.run_status.dec_packages_size += bytesRead;
        }
    }

    DWORD err = GetLastError();
    if (err != ERROR_BROKEN_PIPE) {
        std::cerr << "ReadFile error: " << err << "\n";
    }

    // 进程结束后处理剩余数据
    if (!tempBuffer.empty()) {
        std::cerr << "WARNING: Incomplete TS packet (" << tempBuffer.size() << " bytes)\n";
    }

    CloseHandle(hReadPipe);
    // 强行终止进程
    if (!TerminateProcess(pi.hProcess, 0)) {
        std::cerr << "TerminateProcess failed: " << GetLastError() << std::endl;
    }
    else {
        std::cout << "Process terminated successfully." << std::endl;
    }
    WaitForSingleObject(pi.hProcess, INFINITE);
    CloseHandle(pi.hProcess);
    return true;
}

bool Media::Start(bool rawdata)
{
    if (nullptr == input_fmt_ctx)
    {
        return false;
    }
    if (!stop_flag) {
        return true;
    }
    if (rawdata)
    {
        main_thread = std::thread(Media::MainRawThread, this);
    }
    else
    {
        //main_thread = std::thread(Media::MainThread, this);
        main_thread = std::thread(Media::MainPipeThread, this);
    }
    return true;
}

bool Media::Stop()
{
    stop_flag = true;
    video_packet_queue_cv.notify_all();
    audio_packet_queue_cv.notify_all();
    video_frame_queue_cv.notify_all();
    audio_frame_queue_cv.notify_all();

    if (main_thread.joinable())
    {
        main_thread.join();
    }

    if (nullptr != swr_ctx)
    {
        swr_free(&swr_ctx);
    }
    if (nullptr != audio_enc_ctx)
    {
        avcodec_free_context(&audio_enc_ctx);
    }
    if (nullptr != audio_dec_ctx)
    {
        avcodec_free_context(&audio_dec_ctx);
    }
    if (nullptr != video_enc_ctx)
    {
        avcodec_free_context(&video_enc_ctx);
    }
    if (nullptr != video_dec_ctx)
    {
        avcodec_free_context(&video_dec_ctx);
    }
    if (nullptr != avio_ctx)
    {
        avio_context_free(&avio_ctx);
    }
    if (nullptr != output_ctx)
    {
        avformat_free_context(output_ctx);
        output_ctx = nullptr;
    }
    sws_ctx = nullptr;    

    return true;
}

bool Media::Seek(double seconds)
{
    /*
    if (nullptr == input_fmt_ctx || -1 == audio_idx)
    {
        return false;
    }
    AVStream* in_stream = input_fmt_ctx->streams[audio_idx];
    seek_target_ = seconds / av_q2d(in_stream->time_base);
    */
    seek_target_ = (int64_t)(seconds * AV_TIME_BASE);

    //video_start_time = AV_NOPTS_VALUE;
    //audio_start_time = seek_target_;
    //seek_requested_ = true;
    return true;
}


/*
typedef struct StreamContext {
    AVCodecContext* dec_ctx;
    AVCodecContext* enc_ctx;
    SwsContext* sws_ctx;
    SwrContext* swr_ctx;
} StreamContext;

//std::ofstream debugFile("d:\\out.ts", std::ios::binary);

static int write_queue(void* opaque, uint8_t* buf, int buf_size) {

    //std::unique_lock<std::mutex> lock(ctrl.mutex);
    //if (!ctrl.flushing) 
    {
        // 写入队列操作
        AVFormatContext* fmt_ctx = (AVFormatContext*)opaque;
        std::unique_lock<std::mutex> lock(g_queues.mutex);
        g_queues.ts_packets.push(std::move(std::vector<uint8_t>(buf, buf + buf_size)));
        g_status.queue_count = g_queues.ts_packets.size();
    }

    if (buf_size == DEC_BUFF_SIZE)
    {
        //缓冲区满，应该增加缓冲区
        //std::cout << "write_queue buf_size full" << std::endl;
    }

    g_status.dec_packages_count++;
    g_status.dec_packages_size += buf_size;

    //debugFile.write(reinterpret_cast<const char*>(buf), buf_size);
    //debugFile.flush();

    //出错返回 -1
    return buf_size;
}


// 解码线程
void decode_thread(AVFormatContext* fmt_ctx, EncodingContext* ctx) {

    ctx->exit_decode = false;
    AVFormatContext* input_ctx = fmt_ctx;

    // 查找视频和音频流
    int video_idx = av_find_best_stream(input_ctx, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    int audio_idx = av_find_best_stream(input_ctx, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);

    if (video_idx < 0 && audio_idx < 0) {
        fprintf(stderr, "未找到音视频流\n");
    }

    ctx->video_idx = video_idx;
    ctx->audio_idx = audio_idx;

    StreamContext video_sc = { 0 }, audio_sc = { 0 };

    // 准备输出上下文
    AVFormatContext* output_ctx = NULL;
    avformat_alloc_output_context2(&output_ctx, NULL, "mpegts", NULL);
    AVIOContext* avio_ctx = avio_alloc_context((unsigned char*)av_malloc(DEC_BUFF_SIZE), DEC_BUFF_SIZE, 1, fmt_ctx, NULL, write_queue, NULL);
    output_ctx->pb = avio_ctx;

    // 配置视频流
    if (video_idx >= 0) {
        AVStream* in_stream = input_ctx->streams[video_idx];

        const AVCodec* dec = avcodec_find_decoder(in_stream->codecpar->codec_id);
        video_sc.dec_ctx = avcodec_alloc_context3(dec);
        avcodec_parameters_to_context(video_sc.dec_ctx, in_stream->codecpar);
        avcodec_open2(video_sc.dec_ctx, dec, NULL);

        const AVCodec* enc = avcodec_find_encoder(AV_CODEC_ID_H264);
        video_sc.enc_ctx = avcodec_alloc_context3(enc);
        video_sc.enc_ctx->height = video_sc.dec_ctx->height;
        video_sc.enc_ctx->width = video_sc.dec_ctx->width;
        video_sc.enc_ctx->sample_aspect_ratio = video_sc.dec_ctx->sample_aspect_ratio;

        // 设置视频编码器的像素格式为CUDA支持的格式
        //video_sc.enc_ctx->pix_fmt = AV_PIX_FMT_CUDA;

        video_sc.enc_ctx->pix_fmt = avcodec_find_best_pix_fmt_of_list(enc->pix_fmts, video_sc.dec_ctx->pix_fmt, 0, NULL);
        video_sc.enc_ctx->time_base = av_inv_q(av_d2q(30, 1000)); // 30fps
        av_opt_set(video_sc.enc_ctx->priv_data, "preset", "fast", 0);

        // 视频编码器参数
        av_opt_set(video_sc.enc_ctx->priv_data, "tune", "zerolatency", 0);
        av_opt_set(video_sc.enc_ctx->priv_data, "sync-lookahead", "0", 0);
        av_opt_set(video_sc.enc_ctx->priv_data, "rc-lookahead", "0", 0);
        video_sc.enc_ctx->max_b_frames = 0;
        video_sc.enc_ctx->gop_size = 30;

        avcodec_open2(video_sc.enc_ctx, enc, NULL);

        AVStream* out_stream = avformat_new_stream(output_ctx, enc);
        avcodec_parameters_from_context(out_stream->codecpar, video_sc.enc_ctx);
        out_stream->time_base = video_sc.enc_ctx->time_base;

        video_sc.sws_ctx = sws_getContext(
            video_sc.dec_ctx->width, video_sc.dec_ctx->height, video_sc.dec_ctx->pix_fmt,
            video_sc.enc_ctx->width, video_sc.enc_ctx->height, video_sc.enc_ctx->pix_fmt,
            SWS_BILINEAR, NULL, NULL, NULL);

        ctx->video_stream = in_stream;

    }

    // 配置音频流（类似视频，需处理重采样）
    if (audio_idx >= 0) {
        AVStream* in_stream = input_ctx->streams[audio_idx];
        const AVCodec* dec = avcodec_find_decoder(in_stream->codecpar->codec_id);
        audio_sc.dec_ctx = avcodec_alloc_context3(dec);
        avcodec_parameters_to_context(audio_sc.dec_ctx, in_stream->codecpar);
        avcodec_open2(audio_sc.dec_ctx, dec, NULL);

        const AVCodec* enc = avcodec_find_encoder(AV_CODEC_ID_AAC);
        audio_sc.enc_ctx = avcodec_alloc_context3(enc);

        // 设置音频编码参数（新API方式）
        audio_sc.enc_ctx->sample_rate = 48000;
        audio_sc.enc_ctx->sample_fmt = enc->sample_fmts[0]; // 选择第一个支持的格式

        // 使用新的通道布局系统
        AVChannelLayout stereo_layout;
        av_channel_layout_default(&stereo_layout, 2); // 立体声
        av_channel_layout_copy(&audio_sc.enc_ctx->ch_layout, &stereo_layout);

        audio_sc.enc_ctx->bit_rate = 128000;
        audio_sc.enc_ctx->time_base = { 1, audio_sc.enc_ctx->sample_rate };

        av_opt_set(audio_sc.enc_ctx->priv_data, "aac_coder", "fast", 0);
        audio_sc.enc_ctx->frame_size = 1024; // AAC每帧1024样本

        if (avcodec_open2(audio_sc.enc_ctx, enc, NULL) < 0) {
            fprintf(stderr, "无法打开音频编码器\n");
        }

        AVStream* out_stream = avformat_new_stream(output_ctx, enc);
        avcodec_parameters_from_context(out_stream->codecpar, audio_sc.enc_ctx);
        out_stream->time_base = audio_sc.enc_ctx->time_base;

        // 初始化音频重采样器（新API）
        audio_sc.swr_ctx = swr_alloc();
        swr_alloc_set_opts2(&audio_sc.swr_ctx,
            &audio_sc.enc_ctx->ch_layout, // 输出布局
            audio_sc.enc_ctx->sample_fmt,
            audio_sc.enc_ctx->sample_rate,
            &audio_sc.dec_ctx->ch_layout, // 输入布局
            audio_sc.dec_ctx->sample_fmt,
            audio_sc.dec_ctx->sample_rate,
            0, NULL);
        swr_init(audio_sc.swr_ctx);
    }

    const AVRational TS_TIME_BASE = { 1, 90000 }; // MPEG-TS标准时间基

    // 视频编码器设置
    video_sc.enc_ctx->time_base = TS_TIME_BASE;
    video_sc.enc_ctx->framerate = { 30, 1 }; // 明确设置帧率

    // 音频编码器设置
    audio_sc.enc_ctx->time_base = TS_TIME_BASE; // 统一使用相同时间基
    audio_sc.enc_ctx->sample_rate = 48000;

    // 输出流设置
    output_ctx->streams[0]->time_base = TS_TIME_BASE;
    output_ctx->streams[1]->time_base = TS_TIME_BASE;


    // 写入输出头部
    avformat_write_header(output_ctx, NULL);

    AVPacket* pkt = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();

    while (!ctx->exit_decode) {

        while (!ctx->exit_decode)
        {
            bool wait = false;
            {
                std::lock_guard<std::mutex> lock(g_queues.mutex);
                if (g_queues.ts_packets.size() >= MAX_QUEUE_SIZE)
                {
                    wait = true;
                }
            }
            if (wait)
            {
                Sleep(10);
            }
            else
            {
                break;
            }
        }

        
        {
            std::unique_lock<std::mutex> lock(ctrl.mutex);
            if (ctrl.seek_requested)
            {
                int ret = avformat_seek_file(input_ctx, -1,
                    ctrl.seek_target_ts - 100000,
                    ctrl.seek_target_ts,
                    ctrl.seek_target_ts + 100000,
                    AVSEEK_FLAG_BACKWARD | AVSEEK_FLAG_FRAME);

                if (ret >= 0) {
                    ctrl.flushing = true;
                    ctrl.seek_requested = false;
                    std::cout << "Seek to: " << ctrl.seek_target_ts / AV_TIME_BASE
                        << "s\n";
                }
            }
            lock.unlock();
        }

        int ret = av_read_frame(input_ctx, pkt);
        if (ret < 0) {
            // 处理文件结束或错误
            break;
        }

        // 记录当前PTS（用于进度显示）
        {
            std::lock_guard<std::mutex> lock(ctrl.mutex);
            if (pkt->stream_index == video_idx) {
                ctrl.current_pts = av_rescale_q(pkt->pts,
                    input_ctx->streams[video_idx]->time_base,
                    AV_TIME_BASE_Q);
            }
        }

        if (pkt->stream_index == video_idx) {
            // 解码视频帧
            avcodec_send_packet(video_sc.dec_ctx, pkt);
            while (avcodec_receive_frame(video_sc.dec_ctx, frame) == 0) {
                if (ctx->exit_decode)
                {
                    break;
                }
                // 转换帧格式
                AVFrame* enc_frame = av_frame_alloc();
                enc_frame->format = video_sc.enc_ctx->pix_fmt;
                enc_frame->width = video_sc.enc_ctx->width;
                enc_frame->height = video_sc.enc_ctx->height;
                av_frame_get_buffer(enc_frame, 0);
                sws_scale(video_sc.sws_ctx, frame->data, frame->linesize, 0,
                    frame->height, enc_frame->data, enc_frame->linesize);

                // 编码并发送
                avcodec_send_frame(video_sc.enc_ctx, enc_frame);
                AVPacket* enc_pkt = av_packet_alloc();
                while (avcodec_receive_packet(video_sc.enc_ctx, enc_pkt) == 0) {
                    if (ctx->exit_decode)
                    {
                        break;
                    }

                    // 基于系统时钟生成PTS
                    static auto start_time = std::chrono::steady_clock::now();
                    auto now = std::chrono::steady_clock::now();
                    int64_t elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(now - start_time).count();

                    enc_pkt->pts = av_rescale_q(elapsed_ms,
                        {
                        1, 1000
                    },
                        TS_TIME_BASE);
                    enc_pkt->dts = enc_pkt->pts;

                    // 强制递增保护
                    static int64_t last_pts = AV_NOPTS_VALUE;
                    if (last_pts != AV_NOPTS_VALUE) {
                        if (enc_pkt->pts <= last_pts) {
                            enc_pkt->pts = last_pts + av_rescale_q(1, video_sc.enc_ctx->time_base, TS_TIME_BASE);
                        }
                        last_pts = enc_pkt->pts;
                        enc_pkt->dts = enc_pkt->pts;
                    }
                    else {
                        last_pts = enc_pkt->pts;
                    }

                    // 确保DTS <= PTS
                    if (enc_pkt->dts > enc_pkt->pts) {
                        enc_pkt->dts = enc_pkt->pts;
                    }

                    // 直接使用统一时间基，无需rescale
                    enc_pkt->stream_index = 0;
                    av_interleaved_write_frame(output_ctx, enc_pkt);
                    av_packet_unref(enc_pkt);
                }
                {
                    std::lock_guard<std::mutex> lock(ctrl.mutex);
                    ctrl.current_pts = av_rescale_q(enc_pkt->pts,
                        output_ctx->streams[0]->time_base,
                        AV_TIME_BASE_Q);
                }
                av_packet_free(&enc_pkt);
                av_frame_free(&enc_frame);
            }
        }
        else if (pkt->stream_index == audio_idx) {
            avcodec_send_packet(audio_sc.dec_ctx, pkt);
            while (avcodec_receive_frame(audio_sc.dec_ctx, frame) == 0) {
                if (ctx->exit_decode)
                {
                    break;
                }
                AVFrame* resampled_frame = av_frame_alloc();

                // 使用新API设置音频帧参数
                resampled_frame->sample_rate = audio_sc.enc_ctx->sample_rate;
                av_channel_layout_copy(&resampled_frame->ch_layout, &audio_sc.enc_ctx->ch_layout);
                resampled_frame->format = audio_sc.enc_ctx->sample_fmt;
                resampled_frame->nb_samples = audio_sc.enc_ctx->frame_size;

                av_frame_get_buffer(resampled_frame, 0);

                // 执行重采样
                swr_convert_frame(audio_sc.swr_ctx, resampled_frame, frame);

                // 编码并发送
                // 计算音频PTS
                static int64_t next_audio_pts = 0;
                resampled_frame->pts = next_audio_pts;
                next_audio_pts += resampled_frame->nb_samples;

                avcodec_send_frame(audio_sc.enc_ctx, resampled_frame);
                AVPacket* enc_pkt = av_packet_alloc();
                while (avcodec_receive_packet(audio_sc.enc_ctx, enc_pkt) == 0) {
                    if (ctx->exit_decode)
                    {
                        break;
                    }
                    // 音频处理部分修改
                    static int64_t audio_samples_sent = 0;

                    // 在编码循环内：
                    enc_pkt->pts = av_rescale_q(audio_samples_sent,
                        {
                        1, audio_sc.enc_ctx->sample_rate
                    },
                        TS_TIME_BASE);
                    audio_samples_sent += resampled_frame->nb_samples;

                    // 强制DTS同步
                    enc_pkt->dts = enc_pkt->pts;

                    // 交叉检查视频时间戳
                    static int64_t last_video_pts = 0;
                    if (last_video_pts > 0 && enc_pkt->pts < last_video_pts) {
                        audio_samples_sent = av_rescale_q(last_video_pts,
                            TS_TIME_BASE,
                            {
                            1, audio_sc.enc_ctx->sample_rate
                        });
                        enc_pkt->pts = last_video_pts;
                        enc_pkt->dts = enc_pkt->pts;
                    }

                    av_packet_rescale_ts(enc_pkt, TS_TIME_BASE, TS_TIME_BASE); // 显式统一时间基

                    enc_pkt->stream_index = 1;
                    av_interleaved_write_frame(output_ctx, enc_pkt);

                    // 在音频包处理部分添加：
                    {
                        std::lock_guard<std::mutex> lock(ctrl.mutex);
                        if (ctrl.current_pts == AV_NOPTS_VALUE) {
                            ctrl.current_pts = av_rescale_q(enc_pkt->pts,
                                output_ctx->streams[1]->time_base,
                                AV_TIME_BASE_Q);
                        }
                    }

                    av_packet_unref(enc_pkt);
                }

                av_frame_free(&resampled_frame);
            }
        }
        av_packet_unref(pkt);
    }
    while (!ctx->exit_decode)
    {
        Sleep(10);
    }

    // 收尾工作
    av_write_trailer(output_ctx);
    
    avformat_free_context(output_ctx);
    avio_context_free(&avio_ctx);

    if (nullptr != video_sc.enc_ctx)
    {
        avcodec_free_context(&video_sc.enc_ctx);
    }
    if (nullptr != video_sc.dec_ctx)
    {
        avcodec_free_context(&video_sc.dec_ctx);
    }
    if (nullptr != audio_sc.enc_ctx)
    {
        avcodec_free_context(&audio_sc.enc_ctx);
    }
    if (nullptr != audio_sc.dec_ctx)
    {
        avcodec_free_context(&audio_sc.dec_ctx);
    }

    avformat_close_input(&input_ctx);
    delete ctx;
}


void request_seek(PlayerControl& ctrl, double target_seconds) {
    std::lock_guard<std::mutex> lock(ctrl.mutex);
    ctrl.seek_target_ts = static_cast<int64_t>(target_seconds * AV_TIME_BASE);
    ctrl.seek_requested = true;
    ctrl.cv.notify_all();
}

// 状态查询接口
double get_current_time(PlayerControl& ctrl) {
    std::lock_guard<std::mutex> lock(ctrl.mutex);
    return ctrl.current_pts * av_q2d(AV_TIME_BASE_Q);
}


static int write_packet(void* opaque, uint8_t* buf, int buf_size) {
    std::ofstream& outFile = *(std::ofstream*)opaque;
    outFile.write(reinterpret_cast<const char*>(buf), buf_size);
    outFile.flush();
    //出错返回 -1
    return buf_size;
}



int test()
{
#define OUTPUT_URL "rtmp://192.168.2.80:1935/tvstream"  // 目标 IP 地址和端口
    std::string _input_filename = convertToUTF8("G:\\迅雷下载\\魔鬼天使.Devil.Anger.1995.DVDRip.国粤双语.中字.mkv");

    const char* input_filename = _input_filename.c_str();
    
    std::ofstream outFile("d:\\test.ts", std::ios::binary);

    // 打开输入文件
    AVFormatContext* input_ctx = NULL;
    if (avformat_open_input(&input_ctx, input_filename, NULL, NULL) < 0) {
        fprintf(stderr, "无法打开输入文件\n");
        return -1;
    }
    if (avformat_find_stream_info(input_ctx, NULL) < 0) {
        fprintf(stderr, "无法获取流信息\n");
        return -1;
    }

    // 查找视频和音频流
    int video_idx = av_find_best_stream(input_ctx, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    int audio_idx = av_find_best_stream(input_ctx, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);

    if (video_idx < 0 && audio_idx < 0) {
        fprintf(stderr, "未找到音视频流\n");
        return -1;
    }

    StreamContext video_sc = { 0 }, audio_sc = { 0 };

    // 准备输出上下文
    AVFormatContext* output_ctx = NULL;
    avformat_alloc_output_context2(&output_ctx, NULL, "mpegts", NULL);
    AVIOContext* avio_ctx = avio_alloc_context((unsigned char*)av_malloc(4096), 4096, 1, &outFile, NULL, write_packet, NULL);
    output_ctx->pb = avio_ctx;

    // 配置视频流
    if (video_idx >= 0) {
        AVStream* in_stream = input_ctx->streams[video_idx];
        const AVCodec* dec = avcodec_find_decoder(in_stream->codecpar->codec_id);
        video_sc.dec_ctx = avcodec_alloc_context3(dec);
        avcodec_parameters_to_context(video_sc.dec_ctx, in_stream->codecpar);
        avcodec_open2(video_sc.dec_ctx, dec, NULL);

        const AVCodec* enc = avcodec_find_encoder(AV_CODEC_ID_H264);
        video_sc.enc_ctx = avcodec_alloc_context3(enc);
        video_sc.enc_ctx->height = video_sc.dec_ctx->height;
        video_sc.enc_ctx->width = video_sc.dec_ctx->width;
        video_sc.enc_ctx->sample_aspect_ratio = video_sc.dec_ctx->sample_aspect_ratio;

        video_sc.enc_ctx->pix_fmt = avcodec_find_best_pix_fmt_of_list(enc->pix_fmts, video_sc.dec_ctx->pix_fmt, 0, NULL);
        video_sc.enc_ctx->time_base = av_inv_q(av_d2q(30, 1000)); // 30fps
        av_opt_set(video_sc.enc_ctx->priv_data, "preset", "fast", 0);
        avcodec_open2(video_sc.enc_ctx, enc, NULL);

        AVStream* out_stream = avformat_new_stream(output_ctx, enc);
        avcodec_parameters_from_context(out_stream->codecpar, video_sc.enc_ctx);
        out_stream->time_base = video_sc.enc_ctx->time_base;

        video_sc.sws_ctx = sws_getContext(
            video_sc.dec_ctx->width, video_sc.dec_ctx->height, video_sc.dec_ctx->pix_fmt,
            video_sc.enc_ctx->width, video_sc.enc_ctx->height, video_sc.enc_ctx->pix_fmt,
            SWS_BILINEAR, NULL, NULL, NULL);

    }

    // 配置音频流（类似视频，需处理重采样）
    if (audio_idx >= 0) {
        AVStream* in_stream = input_ctx->streams[audio_idx];
        const AVCodec* dec = avcodec_find_decoder(in_stream->codecpar->codec_id);
        audio_sc.dec_ctx = avcodec_alloc_context3(dec);
        avcodec_parameters_to_context(audio_sc.dec_ctx, in_stream->codecpar);
        avcodec_open2(audio_sc.dec_ctx, dec, NULL);

        const AVCodec* enc = avcodec_find_encoder(AV_CODEC_ID_AAC);
        audio_sc.enc_ctx = avcodec_alloc_context3(enc);

        // 设置音频编码参数（新API方式）
        audio_sc.enc_ctx->sample_rate = 48000;
        audio_sc.enc_ctx->sample_fmt = enc->sample_fmts[0]; // 选择第一个支持的格式

        // 使用新的通道布局系统
        AVChannelLayout stereo_layout;
        av_channel_layout_default(&stereo_layout, 2); // 立体声
        av_channel_layout_copy(&audio_sc.enc_ctx->ch_layout, &stereo_layout);

        audio_sc.enc_ctx->bit_rate = 128000;
        audio_sc.enc_ctx->time_base = { 1, audio_sc.enc_ctx->sample_rate };

        if (avcodec_open2(audio_sc.enc_ctx, enc, NULL) < 0) {
            fprintf(stderr, "无法打开音频编码器\n");
            return -1;
        }

        AVStream* out_stream = avformat_new_stream(output_ctx, enc);
        avcodec_parameters_from_context(out_stream->codecpar, audio_sc.enc_ctx);
        out_stream->time_base = audio_sc.enc_ctx->time_base;

        // 初始化音频重采样器（新API）
        audio_sc.swr_ctx = swr_alloc();
        swr_alloc_set_opts2(&audio_sc.swr_ctx,
            &audio_sc.enc_ctx->ch_layout, // 输出布局
            audio_sc.enc_ctx->sample_fmt,
            audio_sc.enc_ctx->sample_rate,
            &audio_sc.dec_ctx->ch_layout, // 输入布局
            audio_sc.dec_ctx->sample_fmt,
            audio_sc.dec_ctx->sample_rate,
            0, NULL);
        swr_init(audio_sc.swr_ctx);
    }

    // 写入输出头部
    avformat_write_header(output_ctx, NULL);

    AVPacket* pkt = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();

    int i = 0;

    while (av_read_frame(input_ctx, pkt) >= 0 && i < 10000) {
        if (pkt->stream_index == video_idx) {
            // 解码视频帧
            avcodec_send_packet(video_sc.dec_ctx, pkt);
            while (avcodec_receive_frame(video_sc.dec_ctx, frame) == 0) {
                // 转换帧格式
                AVFrame* enc_frame = av_frame_alloc();
                enc_frame->format = video_sc.enc_ctx->pix_fmt;
                enc_frame->width = video_sc.enc_ctx->width;
                enc_frame->height = video_sc.enc_ctx->height;
                av_frame_get_buffer(enc_frame, 0);
                sws_scale(video_sc.sws_ctx, frame->data, frame->linesize, 0,
                    frame->height, enc_frame->data, enc_frame->linesize);

                // 编码并发送
                avcodec_send_frame(video_sc.enc_ctx, enc_frame);
                AVPacket *enc_pkt = av_packet_alloc();
                while (avcodec_receive_packet(video_sc.enc_ctx, enc_pkt) == 0) {
                    // 继承原始帧的PTS
                    enc_pkt->pts = av_rescale_q(frame->pts,
                        video_sc.dec_ctx->time_base,
                        video_sc.enc_ctx->time_base);
                    enc_pkt->dts = enc_pkt->pts;

                    // 确保时间戳递增
                    static int64_t last_video_pts = AV_NOPTS_VALUE;
                    if (last_video_pts != AV_NOPTS_VALUE && enc_pkt->pts <= last_video_pts) {
                        enc_pkt->pts = last_video_pts + 1;
                        enc_pkt->dts = enc_pkt->pts;
                    }
                    last_video_pts = enc_pkt->pts;

                    enc_pkt->stream_index = 0;
                    av_packet_rescale_ts(enc_pkt,
                        video_sc.enc_ctx->time_base,
                        output_ctx->streams[0]->time_base);
                    av_interleaved_write_frame(output_ctx, enc_pkt);
                    av_packet_unref(enc_pkt);
                }
                av_packet_free(&enc_pkt);
                av_frame_free(&enc_frame);
            }
        }
        else if (pkt->stream_index == audio_idx) {
            avcodec_send_packet(audio_sc.dec_ctx, pkt);
            while (avcodec_receive_frame(audio_sc.dec_ctx, frame) == 0) {
                AVFrame* resampled_frame = av_frame_alloc();

                // 使用新API设置音频帧参数
                resampled_frame->sample_rate = audio_sc.enc_ctx->sample_rate;
                av_channel_layout_copy(&resampled_frame->ch_layout, &audio_sc.enc_ctx->ch_layout);
                resampled_frame->format = audio_sc.enc_ctx->sample_fmt;
                resampled_frame->nb_samples = audio_sc.enc_ctx->frame_size;

                av_frame_get_buffer(resampled_frame, 0);

                // 执行重采样
                swr_convert_frame(audio_sc.swr_ctx, resampled_frame, frame);

                // 编码并发送
                // 计算音频PTS
                static int64_t next_audio_pts = 0;
                resampled_frame->pts = next_audio_pts;
                next_audio_pts += resampled_frame->nb_samples;

                avcodec_send_frame(audio_sc.enc_ctx, resampled_frame);
                AVPacket* enc_pkt = av_packet_alloc();
                while (avcodec_receive_packet(audio_sc.enc_ctx, enc_pkt) == 0) {
                    // 转换时间基准
                    enc_pkt->pts = av_rescale_q(enc_pkt->pts,
                        audio_sc.enc_ctx->time_base,
                        output_ctx->streams[1]->time_base);
                    enc_pkt->dts = enc_pkt->pts;

                    // 确保音频PTS不落后视频
                    static int64_t last_audio_pts = AV_NOPTS_VALUE;
                    if (last_audio_pts != AV_NOPTS_VALUE &&
                        enc_pkt->pts <= last_audio_pts) {
                        enc_pkt->pts = last_audio_pts + 1;
                        enc_pkt->dts = enc_pkt->pts;
                    }
                    last_audio_pts = enc_pkt->pts;

                    enc_pkt->stream_index = 1;
                    av_interleaved_write_frame(output_ctx, enc_pkt);
                    av_packet_unref(enc_pkt);
                }

                av_frame_free(&resampled_frame);
            }
        }
        av_packet_unref(pkt);
        i++;
    }

    // 收尾工作
    av_write_trailer(output_ctx);
    avformat_close_input(&input_ctx);
    avio_context_free(&output_ctx->pb);
    avformat_free_context(output_ctx);
    
    outFile.close();
    return 0;
}
*/
