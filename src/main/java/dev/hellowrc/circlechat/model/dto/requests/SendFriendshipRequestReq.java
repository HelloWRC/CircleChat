package dev.hellowrc.circlechat.model.dto.requests;

public record SendFriendshipRequestReq(
        String targetUsername,
        String note
) {
}
