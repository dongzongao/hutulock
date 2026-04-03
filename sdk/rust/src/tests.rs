#[cfg(test)]
mod tests {
    use crate::protocol::Message;

    #[test]
    fn test_message_parse_no_args() {
        let msg = Message::parse("CONNECT").unwrap();
        assert_eq!(msg.typ, "CONNECT");
        assert!(msg.args.is_empty());
    }

    #[test]
    fn test_message_parse_with_args() {
        let msg = Message::parse("LOCK order-lock session-123").unwrap();
        assert_eq!(msg.typ, "LOCK");
        assert_eq!(msg.arg(0), Some("order-lock"));
        assert_eq!(msg.arg(1), Some("session-123"));
    }

    #[test]
    fn test_message_serialize() {
        let msg = Message::new("LOCK", &["order-lock", "session-123"]);
        assert_eq!(msg.serialize(), "LOCK order-lock session-123");
    }

    #[test]
    fn test_message_serialize_no_args() {
        let msg = Message::new("CONNECT", &[]);
        assert_eq!(msg.serialize(), "CONNECT");
    }

    #[test]
    fn test_message_parse_lowercase_normalized() {
        let msg = Message::parse("connected session-abc").unwrap();
        assert_eq!(msg.typ, "CONNECTED");
        assert_eq!(msg.arg(0), Some("session-abc"));
    }

    #[test]
    fn test_message_parse_empty_fails() {
        assert!(Message::parse("").is_err());
        assert!(Message::parse("   ").is_err());
    }

    #[test]
    fn test_message_arg_out_of_bounds() {
        let msg = Message::new("OK", &["lock-1"]);
        assert_eq!(msg.arg(0), Some("lock-1"));
        assert_eq!(msg.arg(1), None);
    }
}
