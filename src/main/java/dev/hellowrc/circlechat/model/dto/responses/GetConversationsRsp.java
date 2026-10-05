package dev.hellowrc.circlechat.model.dto.responses;

import dev.hellowrc.circlechat.model.dto.ConversationInfo;

import java.util.List;

public record GetConversationsRsp(
        List<ConversationInfo> conversations,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasMore
) {
}
