package dev.hellowrc.circlechat.model.dto.responses;

public record HttpRequestRsp<T>(
        T content,
        Integer statusCode,
        String message
) {
}
