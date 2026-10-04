package dev.hellowrc.circlechat.model.dto.responses;

import dev.hellowrc.circlechat.model.dto.ChatMessage;

public record ReceiveChatMessageRsp(
        ChatMessage message
) {
}
