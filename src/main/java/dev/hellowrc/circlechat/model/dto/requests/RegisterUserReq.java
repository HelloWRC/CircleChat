package dev.hellowrc.circlechat.model.dto.requests;

public record RegisterUserReq(
        String username,
        String email,

        String password,
        String displayName
) {
}
