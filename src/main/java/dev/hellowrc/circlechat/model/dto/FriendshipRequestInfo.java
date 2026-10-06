package dev.hellowrc.circlechat.model.dto;

import dev.hellowrc.circlechat.model.entitiy.FriendshipRequestState;

import java.time.LocalDateTime;

public record FriendshipRequestInfo(
        Long id,
        String senderUsername,
        String senderDisplayName,
        String senderAvatarUrl,
        String targetUsername,
        String targetDisplayName,
        String targetAvatarUrl,
        String note,
        FriendshipRequestState state,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
