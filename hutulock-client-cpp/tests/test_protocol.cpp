#include "hutulock/protocol.hpp"
#include <gtest/gtest.h>

using namespace hutulock;

TEST(ProtocolTest, EncodeDecodeBasic) {
    Message msg(MessageType::LOCK);
    msg.request_id = "test-123";
    msg.set_header("lockName", "my-lock");
    msg.set_header("sessionId", "session-456");
    msg.set_body("test data");
    
    auto encoded = Protocol::encode(msg);
    EXPECT_GT(encoded.size(), 0);
    
    auto decoded = Protocol::decode(encoded);
    EXPECT_EQ(decoded.type, MessageType::LOCK);
    EXPECT_EQ(decoded.request_id, "test-123");
    EXPECT_EQ(decoded.get_header("lockName"), "my-lock");
    EXPECT_EQ(decoded.get_header("sessionId"), "session-456");
    EXPECT_EQ(decoded.get_body_string(), "test data");
}

TEST(ProtocolTest, EmptyMessage) {
    Message msg(MessageType::HEARTBEAT);
    msg.request_id = "hb-1";
    
    auto encoded = Protocol::encode(msg);
    auto decoded = Protocol::decode(encoded);
    
    EXPECT_EQ(decoded.type, MessageType::HEARTBEAT);
    EXPECT_EQ(decoded.request_id, "hb-1");
    EXPECT_TRUE(decoded.headers.empty());
    EXPECT_TRUE(decoded.body.empty());
}

TEST(ProtocolTest, MultipleHeaders) {
    Message msg(MessageType::LOCK);
    msg.request_id = "req-1";
    msg.set_header("key1", "value1");
    msg.set_header("key2", "value2");
    msg.set_header("key3", "value3");
    
    auto encoded = Protocol::encode(msg);
    auto decoded = Protocol::decode(encoded);
    
    EXPECT_EQ(decoded.headers.size(), 3);
    EXPECT_EQ(decoded.get_header("key1"), "value1");
    EXPECT_EQ(decoded.get_header("key2"), "value2");
    EXPECT_EQ(decoded.get_header("key3"), "value3");
}

TEST(ProtocolTest, BinaryBody) {
    Message msg(MessageType::SET_DATA);
    msg.request_id = "set-1";
    
    std::vector<uint8_t> binary_data = {0x00, 0x01, 0x02, 0xFF, 0xFE};
    msg.body = binary_data;
    
    auto encoded = Protocol::encode(msg);
    auto decoded = Protocol::decode(encoded);
    
    EXPECT_EQ(decoded.body, binary_data);
}

TEST(ProtocolTest, GenerateRequestId) {
    auto id1 = Protocol::generate_request_id();
    auto id2 = Protocol::generate_request_id();
    
    EXPECT_FALSE(id1.empty());
    EXPECT_FALSE(id2.empty());
    EXPECT_NE(id1, id2);
}

TEST(ProtocolTest, HasHeader) {
    Message msg(MessageType::LOCK);
    msg.set_header("key1", "value1");
    
    EXPECT_TRUE(msg.has_header("key1"));
    EXPECT_FALSE(msg.has_header("key2"));
}
