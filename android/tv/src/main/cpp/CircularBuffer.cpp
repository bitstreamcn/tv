#include "CircularBuffer.h"

CircularBuffer::CircularBuffer(size_t capacity) 
    : buffer_(capacity), capacity_(capacity) {}

size_t CircularBuffer::write(const uint8_t* data, size_t size) {
    std::unique_lock<std::mutex> lock(mutex_);
    
    size_t bytes_to_write = std::min(size, capacity_ - available());
    if (bytes_to_write == 0) return 0;

    size_t first_part = std::min(bytes_to_write, capacity_ - write_pos_);
    std::copy(data, data + first_part, buffer_.begin() + write_pos_);
    
    if (bytes_to_write > first_part) {
        std::copy(data + first_part, data + bytes_to_write, buffer_.begin());
    }

    write_pos_ = (write_pos_ + bytes_to_write) % capacity_;
    not_empty_.notify_one();
    return bytes_to_write;
}

size_t CircularBuffer::read(uint8_t* buffer, size_t size) {
    std::unique_lock<std::mutex> lock(mutex_);
    
    size_t bytes_available = available();
    if (bytes_available == 0) return 0;

    size_t bytes_to_read = std::min(size, bytes_available);
    size_t first_part = std::min(bytes_to_read, capacity_ - read_pos_);
    
    std::copy(buffer_.begin() + read_pos_, 
             buffer_.begin() + read_pos_ + first_part, 
             buffer);
    
    if (bytes_to_read > first_part) {
        std::copy(buffer_.begin(), 
                 buffer_.begin() + (bytes_to_read - first_part),
                 buffer + first_part);
    }

    read_pos_ = (read_pos_ + bytes_to_read) % capacity_;
    not_full_.notify_one();
    return bytes_to_read;
}

size_t CircularBuffer::available() const {
    return (write_pos_ >= read_pos_) ? 
        (write_pos_ - read_pos_) : 
        (capacity_ - read_pos_ + write_pos_);
}

void CircularBuffer::reset() {
    std::lock_guard<std::mutex> lock(mutex_);
    read_pos_ = 0;
    write_pos_ = 0;
}