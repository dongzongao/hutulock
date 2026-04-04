#pragma once

#include <string>
#include <vector>
#include <map>
#include <cstdint>

namespace hutulock {

/**
 * 协议消息类型
 */
enum class MessageType : uint8_t {
    CONNECT = 1,
    CONNECT_ACK = 2,
    LOCK = 3,
    LOCK_ACK = 4,
    UNLOCK = 5,
    UNLOCK_ACK = 6,
    HEARTBEAT = 7,
    HEARTBEAT_ACK = 8,
    WATCH = 9,
    WATCH_EVENT = 10,
    GET_DATA = 11,
    GET_DATA_ACK = 12,
    SET_DATA = 13,
    SET_DATA_ACK = 14,
    REDIRECT = 15,
    ERROR = 99
};

/**
 * 协议消息
 */
struct Message {
    MessageType type;
    std::string request_id;
    std::map<std::string, std::string> headers;
    std::vector<uint8_t> body;
    
    Message() = default;
    Message(MessageType t) : type(t) {}
    
    // 辅助方法
    void set_header(const std::string& key, const std::string& value) {
        headers[key] = value;
    }
    
    std::string get_header(const std::string& key) const {
        auto it = headers.find(key);
        return it != headers.end() ? it->second : "";
    }
    
    bool has_header(const std::string& key) const {
        return headers.find(key) != headers.end();
    }
    
    void set_body(const std::string& data) {
        body.assign(data.begin(), data.end());
    }
    
    std::string get_body_string() const {
        return std::string(body.begin(), body.end());
    }
};

/**
 * 协议编解码器
 */
class Protocol {
public:
    /**
     * 编码消息为字节流
     * @param msg 消息
     * @return 字节流
     */
    static std::vector<uint8_t> encode(const Message& msg);
    
    /**
     * 解码字节流为消息
     * @param data 字节流
     * @return 消息，失败返回空
     */
    static Message decode(const std::vector<uint8_t>& data);
    
    /**
     * 生成请求 ID
     */
    static std::string generate_request_id();
    
private:
    static void write_uint8(std::vector<uint8_t>& buf, uint8_t value);
    static void write_uint16(std::vector<uint8_t>& buf, uint16_t value);
    static void write_uint32(std::vector<uint8_t>& buf, uint32_t value);
    static void write_string(std::vector<uint8_t>& buf, const std::string& str);
    
    static uint8_t read_uint8(const uint8_t*& ptr);
    static uint16_t read_uint16(const uint8_t*& ptr);
    static uint32_t read_uint32(const uint8_t*& ptr);
    static std::string read_string(const uint8_t*& ptr, const uint8_t* end);
};

} // namespace hutulock
