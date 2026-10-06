package dev.hellowrc.circlechat.model.dto.responses;

import dev.hellowrc.circlechat.model.dto.FriendInfo;

import java.util.List;

public record FriendsMyRsp(
        List<FriendInfo> friends
) {
}
