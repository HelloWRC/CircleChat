package dev.hellowrc.circlechat.model.dto;

import java.time.LocalDateTime;

public record UserInfo(
        Long id,
        String username,
        String displayName,
        String email,
        String avatarUrl,
        String avatarLargeUrl,
        String avatarSmallUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
