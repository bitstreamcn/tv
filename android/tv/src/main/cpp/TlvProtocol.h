// TlvProtocol.h
#pragma once
#include <cstdint>
#include <vector>

class TlvProtocol {
public:
    static const uint32_t MAGIC_T = 0x7a321465;
    
    struct TlvPacket {
        uint32_t type;
        uint32_t length;
        std::vector<uint8_t> value;
    };

    static std::vector<uint8_t> buildPacket(uint32_t type, const uint8_t* data, size_t length);
    static bool parsePacket(const uint8_t* data, size_t length, TlvPacket& packet);
    
private:
    static uint32_t hostToNetwork(uint32_t value);
    static uint32_t networkToHost(uint32_t value);
};