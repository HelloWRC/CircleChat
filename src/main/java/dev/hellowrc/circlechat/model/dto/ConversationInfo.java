package dev.hellowrc.circlechat.model.dto;

public record ConversationInfo(
    Long id,
    String title,
    boolean hasNewMessage,
    boolean isMuted,
    ConversationType type
) {
}
