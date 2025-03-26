// TlvProtocol.cpp
#include "TlvProtocol.h"
#include <arpa/inet.h>

std::vector<uint8_t> TlvProtocol::buildPacket(uint32_t type, const uint8_t* data, size_t length) {
    std::vector<uint8_t> packet;
    uint32_t netType = hostToNetwork(type);
    uint32_t netLength = hostToNetwork(static_cast<uint32_t>(length));
    
    packet.insert(packet.end(), 
        reinterpret_cast<uint8_t*>(&netType),
        reinterpret_cast<uint8_t*>(&netType) + 4);
    
    packet.insert(packet.end(), 
        reinterpret_cast<uint8_t*>(&netLength),
        reinterpret_cast<uint8_t*>(&netLength) + 4);
    
    packet.insert(packet.end(), data, data + length);
    return packet;
}

bool TlvProtocol::parsePacket(const uint8_t* data, size_t length, TlvPacket& packet) {
    if (length < 8) return false;
    
    packet.type = networkToHost(*reinterpret_cast<const uint32_t*>(data));
    packet.length = networkToHost(*reinterpret_cast<const uint32_t*>(data + 4));
    
    if (length < 8 + packet.length) return false;
    
    packet.value.assign(data + 8, data + 8 + packet.length);
    return true;
}

uint32_t TlvProtocol::hostToNetwork(uint32_t value) {
    return htonl(value);
}

uint32_t TlvProtocol::networkToHost(uint32_t value) {
    return ntohl(value);
}