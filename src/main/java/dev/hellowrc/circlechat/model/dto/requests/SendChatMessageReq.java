package dev.hellowrc.circlechat.model.dto.requests;

import dev.hellowrc.circlechat.model.chat.ChatMessage;

public record SendChatMessageReq(
        String message
) {
}
