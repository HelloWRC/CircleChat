package dev.hellowrc.circlechat.service;

import dev.hellowrc.circlechat.exception.ApiException;
import dev.hellowrc.circlechat.model.dto.FriendInfo;
import dev.hellowrc.circlechat.model.dto.FriendshipRequestInfo;
import dev.hellowrc.circlechat.model.dto.PageDto;
import dev.hellowrc.circlechat.model.entitiy.Friendship;
import dev.hellowrc.circlechat.model.entitiy.FriendshipRequest;
import dev.hellowrc.circlechat.model.entitiy.FriendshipRequestState;
import dev.hellowrc.circlechat.model.entitiy.User;
import dev.hellowrc.circlechat.repository.*;
import dev.hellowrc.circlechat.utils.GravatarUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FriendsService {
    private final IFriendshipsRepository friendshipsRepository;
    private final IUsersRepository usersRepository;
    private final IFriendshipRequestsRepository friendshipRequestsRepository;
    private final ConversationService conversationService;
    private final IConversationParticipantsRepository participantsRepository;
    private final IConversationsRepository conversationsRepository;

    public FriendsService(IFriendshipsRepository friendshipsRepository, IUsersRepository usersRepository,
                          IFriendshipRequestsRepository friendshipRequestsRepository,
                          ConversationService conversationService,
                          IConversationParticipantsRepository participantsRepository,
                          IConversationsRepository conversationsRepository) {
        this.friendshipsRepository = friendshipsRepository;
        this.usersRepository = usersRepository;
        this.friendshipRequestsRepository = friendshipRequestsRepository;
        this.conversationService = conversationService;
        this.participantsRepository = participantsRepository;
        this.conversationsRepository = conversationsRepository;
    }

    private FriendInfo createFriendInfoFromUser(User user, boolean isFriend) {

        return new FriendInfo(user.getId(), user.getUsername(), user.getDisplayName(),
                GravatarUtils.getAvatarUrl(user.getEmail()), isFriend);
    }

    private FriendshipRequestInfo toRequestInfo(FriendshipRequest request) {
        var sender = request.getSender();
        var target = request.getTarget();
        return new FriendshipRequestInfo(request.getId(),
                sender.getUsername(), sender.getDisplayName(), GravatarUtils.getAvatarUrl(sender.getEmail()),
                target.getUsername(), target.getDisplayName(), GravatarUtils.getAvatarUrl(target.getEmail()),
                request.getNote(), request.getState(), request.getCreatedAt(), request.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public List<FriendInfo> getFriendsByUsername(String username) {
        var users = friendshipsRepository.findFriends(username);
        return users.stream().map(user -> createFriendInfoFromUser(user, true)).toList();
    }

    @Transactional(readOnly = true)
    public FriendInfo getFriendInfoByUsername(String username, String targetUsername) {
        var user = usersRepository.findByUsername(targetUsername);
        if (user == null) {
            throw new ApiException(404, "找不到对应的用户");
        }
        var isFriend = friendshipsRepository.existsFriendshipOf(username, targetUsername);
        return createFriendInfoFromUser(user, isFriend);
    }

    @Transactional(readOnly = true)
    public PageDto<FriendshipRequestInfo> getFriendshipRequests(String username, boolean sent,
            FriendshipRequestState state, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new ApiException("page 必须非负，size 必须在 1–100 之间，分页偏移不能超过整数范围");
        }
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        var result = sent
                ? friendshipRequestsRepository.findSentRequests(username, state, pageable)
                : friendshipRequestsRepository.findReceivedRequests(username, state, pageable);
        return new PageDto<>(result.getContent().stream().map(this::toRequestInfo).toList(),
                page, size, result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    @Transactional
    public FriendshipRequestInfo sendFriendshipRequest(String username, String targetUsername, String note) {
        validateTargetUsername(username, targetUsername);
        if (note != null && note.length() > 255) {
            throw new ApiException("好友请求备注不能超过 255 个字符");
        }
        lockUsers(username, targetUsername);
        if (friendshipRequestsRepository.existsOpeningRequests(username, targetUsername)) {
            throw new ApiException(409, "已有发送的好友请求");
        }
        if (friendshipRequestsRepository.existsOpeningRequests(targetUsername, username)) {
            throw new ApiException(409, "对方已发送好友请求，请处理收到的请求");
        }
        if (friendshipsRepository.existsFriendshipOf(username, targetUsername)) {
            throw new ApiException(409, "二者已是好友关系");
        }

        var request = new FriendshipRequest();
        request.setSender(usersRepository.findByUsername(username));
        request.setTarget(usersRepository.findByUsername(targetUsername));
        request.setNote(note);
        return toRequestInfo(friendshipRequestsRepository.save(request));
    }

    @Transactional
    public void acceptFriendshipRequest(String username, Long id) {
        var request = getOpenReceivedRequest(username, id);
        var sender = request.getSender();
        var target = request.getTarget();
        lockUsers(sender.getUsername(), target.getUsername());
        addFriendRelationshipCore(sender, target);
        request.setState(FriendshipRequestState.Accepted);
    }

    private void addFriendRelationshipCore(User a, User b) {
        if (a.getId().equals(b.getId())) {
            throw new ApiException("不能添加自己为好友");
        }
        if (friendshipsRepository.existsFriendshipOf(a.getUsername(), b.getUsername())) {
            throw new ApiException(409, "二者已是好友关系");
        }
        var conversation = conversationService.createConversation();
        conversationService.addParticipant(conversation, a);
        conversationService.addParticipant(conversation, b);

        var friendship = new Friendship();
        friendship.setUserA(a);
        friendship.setUserB(b);
        friendship.setConversation(conversation);
        friendshipsRepository.save(friendship);
    }

    @Transactional
    public void rejectFriendshipRequest(String username, Long id, boolean isIgnore) {
        var request = getOpenReceivedRequest(username, id);
        request.setState(isIgnore ? FriendshipRequestState.Ignored : FriendshipRequestState.Rejected);
    }

    private FriendshipRequest getOpenReceivedRequest(String username, Long id) {
        if (id == null || id < 1) {
            throw new ApiException("好友请求 ID 必须为正整数");
        }
        var target = usersRepository.findByUsername(username);
        if (target == null) {
            throw new ApiException(404, "找不到对应的好友请求");
        }
        var request = friendshipRequestsRepository.findReceivedRequestForUpdate(id, target.getId())
                .orElseThrow(() -> new ApiException(404, "找不到对应的好友请求"));
        if (request.getState() != FriendshipRequestState.Open) {
            throw new ApiException(409, "好友请求已处理");
        }
        return request;
    }

    @Transactional
    public void deleteFriend(String username, String targetUsername) {
        validateTargetUsername(username, targetUsername);
        lockUsers(username, targetUsername);
        var friendship = friendshipsRepository.findFriendshipOf(username, targetUsername)
                .orElseThrow(() -> new ApiException(404, "找不到对应的好友关系"));
        var conversationId = friendship.getConversation().getId();
        // Retain the conversation and messages, but revoke both users' membership.
        participantsRepository.deleteByConversationIdAndUserId(conversationId, friendship.getUserA().getId());
        participantsRepository.deleteByConversationIdAndUserId(conversationId, friendship.getUserB().getId());
        friendshipsRepository.delete(friendship);
    }

    private void validateTargetUsername(String username, String targetUsername) {
        if (targetUsername == null || targetUsername.isBlank() || targetUsername.length() > 32) {
            throw new ApiException("目标用户名不能为空且不能超过 32 个字符");
        }
        if (username.equals(targetUsername)) {
            throw new ApiException("不能对自己执行好友操作");
        }
    }

    private void lockUsers(String username, String targetUsername) {
        // Lock in a fixed order so opposite-direction operations cannot deadlock or create duplicates.
        var first = username.compareTo(targetUsername) < 0 ? username : targetUsername;
        var second = username.compareTo(targetUsername) < 0 ? targetUsername : username;
        if (usersRepository.findByUsernameForUpdate(first) == null
                || usersRepository.findByUsernameForUpdate(second) == null) {
            throw new ApiException(404, "找不到对应的用户");
        }
    }

    @Transactional(readOnly = true)
    public Long getConversationIdFromFriendRelationship(String usernameA, String usernameB) {
        var friendship = friendshipsRepository.findFriendshipOf(usernameA, usernameB)
                .orElseThrow(() -> new ApiException(404, "找不到对应的好友关系"));
        return friendship.getConversation().getId();
    }
}
