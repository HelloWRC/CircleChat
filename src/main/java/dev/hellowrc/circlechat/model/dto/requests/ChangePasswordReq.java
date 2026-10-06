package dev.hellowrc.circlechat.model.dto.requests;

public record ChangePasswordReq(String currentPassword, String newPassword) {
}
