#include "hutulock/protocol.hpp"
#include <random>
#include <sstream>
#include <iomanip>
#include <cstring>

namespace hutulock {

std::vector<uint8_t> Protocol::encode(const Message& msg) {
    std::vector<uint8_t> buf;
    buf.reserve(256);
    
    // Type (1 byte)
    write_uint8(buf, static_cast<uint8_t>(msg.type));
    
    // Request ID
    write_string(buf, msg.request_id);
    
    // Headers count
    write_uint16(buf, static_cast<uint16_t>(msg.headers.size()));
    
    // Headers
    for (const auto& [key, value] : msg.headers) {
        write_string(buf, key);
        write_string(buf, value);
    }
    
    // Body length
    write_uint32(buf, static_cast<uint32_t>(msg.body.size()));
    
    // Body
    buf.insert(buf.end(), msg.body.begin(), msg.body.end());
    
    // Prepend total length
    std::vector<uint8_t> result;
    write_uint32(result, static_cast<uint32_t>(buf.size()));
    result.insert(result.end(), buf.begin(), buf.end());
    
    return result;
}

Message Protocol::decode(const std::vector<uint8_t>& data) {
    if (data.size() < 4) {
        return Message();
    }
    
    const uint8_t* ptr = data.data();
    const uint8_t* end = ptr + data.size();
    
    // Total length
    uint32_t total_len = read_uint32(ptr);
    if (total_len + 4 != data.size()) {
        return Message();
    }
    
    // Type
    Message msg;
    msg.type = static_cast<MessageType>(read_uint8(ptr));
    
    // Request ID
    msg.request_id = read_string(ptr, end);
    
    // Headers
    uint16_t header_count = read_uint16(ptr);
    for (uint16_t i = 0; i < header_count; ++i) {
        std::string key = read_string(ptr, end);
        std::string value = read_string(ptr, end);
        msg.headers[key] = value;
    }
    
    // Body
    uint32_t body_len = read_uint32(ptr);
    if (ptr + body_len > end) {
        return Message();
    }
    msg.body.assign(ptr, ptr + body_len);
    
    return msg;
}

std::string Protocol::generate_request_id() {
    static std::random_device rd;
    static std::mt19937_64 gen(rd());
    static std::uniform_int_distribution<uint64_t> dis;
    
    std::ostringstream oss;
    oss << std::hex << std::setfill('0') << std::setw(16) << dis(gen);
    return oss.str();
}

void Protocol::write_uint8(std::vector<uint8_t>& buf, uint8_t value) {
    buf.push_back(value);
}

void Protocol::write_uint16(std::vector<uint8_t>& buf, uint16_t value) {
    buf.push_back((value >> 8) & 0xFF);
    buf.push_back(value & 0xFF);
}

void Protocol::write_uint32(std::vector<uint8_t>& buf, uint32_t value) {
    buf.push_back((value >> 24) & 0xFF);
    buf.push_back((value >> 16) & 0xFF);
    buf.push_back((value >> 8) & 0xFF);
    buf.push_back(value & 0xFF);
}

void Protocol::write_string(std::vector<uint8_t>& buf, const std::string& str) {
    write_uint16(buf, static_cast<uint16_t>(str.size()));
    buf.insert(buf.end(), str.begin(), str.end());
}

uint8_t Protocol::read_uint8(const uint8_t*& ptr) {
    return *ptr++;
}

uint16_t Protocol::read_uint16(const uint8_t*& ptr) {
    uint16_t value = (static_cast<uint16_t>(ptr[0]) << 8) | ptr[1];
    ptr += 2;
    return value;
}

uint32_t Protocol::read_uint32(const uint8_t*& ptr) {
    uint32_t value = (static_cast<uint32_t>(ptr[0]) << 24) |
                     (static_cast<uint32_t>(ptr[1]) << 16) |
                     (static_cast<uint32_t>(ptr[2]) << 8) |
                     ptr[3];
    ptr += 4;
    return value;
}

std::string Protocol::read_string(const uint8_t*& ptr, const uint8_t* end) {
    if (ptr + 2 > end) return "";
    uint16_t len = read_uint16(ptr);
    if (ptr + len > end) return "";
    std::string str(reinterpret_cast<const char*>(ptr), len);
    ptr += len;
    return str;
}

} // namespace hutulock
