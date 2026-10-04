package dev.hellowrc.circlechat.model.dto;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/** A database-independent boundary, including for messages not committed yet. */
public record MessageCursor(long conversationId, LocalDateTime time, String key)
        implements Comparable<MessageCursor> {
    public static MessageCursor empty(long conversationId) {
        return new MessageCursor(conversationId, LocalDateTime.of(1, 1, 1, 0, 0),
                "00000000-0000-0000-0000-000000000000");
    }

    public static MessageCursor of(ChatMessage message) {
        return new MessageCursor(message.conversationId(), message.sendTime(), message.id());
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (conversationId + "|" + time + "|" + key).getBytes(StandardCharsets.UTF_8));
    }

    public static MessageCursor decode(String value, long conversationId) {
        if (value == null) return null;
        try {
            if (value.length() > 200) throw new IllegalArgumentException();
            var parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 3 || Long.parseLong(parts[0]) != conversationId) throw new IllegalArgumentException();
            var time = LocalDateTime.parse(parts[1]);
            if (!time.equals(time.truncatedTo(ChronoUnit.MICROS)) || time.getYear() < 1 || time.getYear() > 9999)
                throw new IllegalArgumentException();
            var key = UUID.fromString(parts[2]).toString();
            if (!key.equals(parts[2])) throw new IllegalArgumentException();
            return new MessageCursor(conversationId, time, key);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("无效的消息游标", exception);
        }
    }

    @Override
    public int compareTo(MessageCursor other) {
        int result = time.compareTo(other.time);
        return result == 0 ? key.compareTo(other.key) : result;
    }
}
