package dev.hellowrc.circlechat.controller;

import dev.hellowrc.circlechat.exception.ApiException;
import dev.hellowrc.circlechat.model.dto.FriendInfo;
import dev.hellowrc.circlechat.model.dto.FriendshipRequestInfo;
import dev.hellowrc.circlechat.model.dto.PageDto;
import dev.hellowrc.circlechat.model.dto.requests.SendFriendshipRequestReq;
import dev.hellowrc.circlechat.model.dto.responses.FriendsMyRsp;
import dev.hellowrc.circlechat.model.dto.responses.GetConversationIdOfFriendRsp;
import dev.hellowrc.circlechat.model.dto.responses.HttpRequestRsp;
import dev.hellowrc.circlechat.model.entitiy.FriendshipRequestState;
import dev.hellowrc.circlechat.service.FriendsService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/friends")
public class FriendsController {
    private final FriendsService friendsService;

    public FriendsController(FriendsService friendsService) {
        this.friendsService = friendsService;
    }

    @GetMapping("/my")
    @Operation(summary = "查询当前用户的好友列表")
    public HttpRequestRsp<FriendsMyRsp> getMyFriends(Principal principal) {
        var username = principal.getName();
        var friends = friendsService.getFriendsByUsername(username);
        return new HttpRequestRsp<>(new FriendsMyRsp(friends), 200, "ok");
    }

    @GetMapping("/user/{username}")
    @Operation(summary = "查询用户公开信息及与当前用户的好友关系")
    public HttpRequestRsp<FriendInfo> getFriendInfo(Principal principal, @PathVariable String username) {
        return new HttpRequestRsp<>(friendsService.getFriendInfoByUsername(principal.getName(), username), 200, "ok");
    }

    @GetMapping("/requests")
    @Operation(summary = "分页查询当前用户的好友请求",
            description = "sent=false 查询收到的请求，sent=true 查询发送的请求。state 不传时查询所有状态；按 ID 倒序排列。")
    public HttpRequestRsp<PageDto<FriendshipRequestInfo>> getFriendshipRequests(Principal principal,
            @RequestParam(defaultValue = "false") boolean sent,
            @RequestParam(required = false) FriendshipRequestState state,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var requests = friendsService.getFriendshipRequests(principal.getName(), sent, state, page, size);
        return new HttpRequestRsp<>(requests, 200, "ok");
    }

    @PostMapping("/requests")
    @Operation(summary = "发送好友请求")
    public HttpRequestRsp<FriendshipRequestInfo> sendFriendshipRequest(Principal principal,
            @RequestBody SendFriendshipRequestReq request) {
        var result = friendsService.sendFriendshipRequest(principal.getName(), request.targetUsername(), request.note());
        return new HttpRequestRsp<>(result, 200, "ok");
    }

    @PostMapping("/requests/{id}/accept")
    @Operation(summary = "接受收到的好友请求并创建双人会话")
    public HttpRequestRsp<Void> acceptFriendshipRequest(Principal principal, @PathVariable Long id) {
        friendsService.acceptFriendshipRequest(principal.getName(), id);
        return new HttpRequestRsp<>(null, 200, "ok");
    }

    @PostMapping("/requests/{id}/reject")
    @Operation(summary = "拒绝收到的好友请求")
    public HttpRequestRsp<Void> rejectFriendshipRequest(Principal principal, @PathVariable Long id) {
        friendsService.rejectFriendshipRequest(principal.getName(), id, false);
        return new HttpRequestRsp<>(null, 200, "ok");
    }

    @PostMapping("/requests/{id}/ignore")
    @Operation(summary = "忽略收到的好友请求")
    public HttpRequestRsp<Void> ignoreFriendshipRequest(Principal principal, @PathVariable Long id) {
        friendsService.rejectFriendshipRequest(principal.getName(), id, true);
        return new HttpRequestRsp<>(null, 200, "ok");
    }

    @DeleteMapping("/user/{username}")
    @Operation(summary = "删除好友",
            description = "删除双方好友关系并撤销双方在该好友会话中的成员权限；保留会话和消息数据。")
    public HttpRequestRsp<Void> deleteFriend(Principal principal, @PathVariable String username) {
        friendsService.deleteFriend(principal.getName(), username);
        return new HttpRequestRsp<>(null, 200, "ok");
    }

    @GetMapping("/user/{username}/conversation")
    @Operation(summary = "获取和某个好友的会话 id")
    public HttpRequestRsp<GetConversationIdOfFriendRsp> getConversationIdOfFriend(Principal principal, @PathVariable String username) {
        return new HttpRequestRsp<>(new GetConversationIdOfFriendRsp(friendsService.getConversationIdFromFriendRelationship(principal.getName(), username)),
                200, "ok");
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<HttpRequestRsp<Void>> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(new HttpRequestRsp<>(null, exception.getStatusCode(), exception.getMessage()));
    }
}
