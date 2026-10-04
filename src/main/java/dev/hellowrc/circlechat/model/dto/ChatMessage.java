package dev.hellowrc.circlechat.model.dto;

import java.time.LocalDateTime;

public record ChatMessage(
        Long id,
        String body,
        String senderDisplayName,
        String senderUsername,
        Long senderId,
        String senderAvatarUrl,
        LocalDateTime sendTime
) {
}
