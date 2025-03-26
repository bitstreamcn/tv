#pragma once
#include <vector>
#include <mutex>
#include <condition_variable>
#include <atomic>

class CircularBuffer {
public:
    CircularBuffer(size_t capacity);
    
    size_t write(const uint8_t* data, size_t size);
    size_t read(uint8_t* buffer, size_t size);
    size_t available() const;
    void reset();
    
private:
    std::vector<uint8_t> buffer_;
    size_t capacity_;
    std::atomic<size_t> read_pos_{0};
    std::atomic<size_t> write_pos_{0};
    mutable std::mutex mutex_;
    std::condition_variable not_full_;
    std::condition_variable not_empty_;
};