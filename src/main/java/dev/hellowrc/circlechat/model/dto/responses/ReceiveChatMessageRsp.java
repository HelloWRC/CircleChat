package dev.hellowrc.circlechat.model.dto.responses;

import dev.hellowrc.circlechat.model.chat.ChatMessage;

public record ReceiveChatMessageRsp(
        String message,
        String senderName
) {
}
