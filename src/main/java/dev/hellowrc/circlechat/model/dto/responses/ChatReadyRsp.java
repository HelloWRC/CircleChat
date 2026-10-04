package dev.hellowrc.circlechat.model.dto.responses;

public record ChatReadyRsp(String requestId, long conversationId, boolean success, String error) {}
