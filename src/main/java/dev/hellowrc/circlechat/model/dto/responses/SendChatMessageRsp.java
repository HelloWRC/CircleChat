package dev.hellowrc.circlechat.model.dto.responses;

public record SendChatMessageRsp(String clientMessageId, boolean success, String error) {
}
