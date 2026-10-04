package dev.hellowrc.circlechat.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

public record ChatMessage(
        String id,
        Long conversationId,
        String body,
        String senderDisplayName,
        String senderUsername,
        Long senderId,
        String senderAvatarUrl,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS") LocalDateTime sendTime
) {
}
