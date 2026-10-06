package dev.hellowrc.circlechat.model.dto;

public record FriendInfo(
        Long userId,
        String username,
        String displayName,
        String avatarUrl,
        boolean isFriend
) {
}
