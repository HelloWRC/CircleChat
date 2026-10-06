package dev.hellowrc.circlechat.service;

import dev.hellowrc.circlechat.model.dto.ConversationInfo;
import dev.hellowrc.circlechat.model.dto.ConversationType;
import dev.hellowrc.circlechat.model.dto.responses.GetConversationsRsp;
import dev.hellowrc.circlechat.model.entitiy.Conversation;
import dev.hellowrc.circlechat.model.entitiy.ConversationParticipant;
import dev.hellowrc.circlechat.model.entitiy.User;
import dev.hellowrc.circlechat.repository.*;
import dev.hellowrc.circlechat.utils.GravatarUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ConversationService {
    private final IConversationsRepository conversationsRepository;
    private final IConversationParticipantsRepository conversationParticipantsRepository;
    private final IChatroomsRepository chatroomsRepository;
    private final IFriendshipsRepository friendshipsRepository;
    private final IMessagesRepository messagesRepository;

    public ConversationService(IConversationsRepository conversationsRepository,
                               IConversationParticipantsRepository conversationParticipantsRepository,
                               IChatroomsRepository chatroomsRepository,
                               IFriendshipsRepository friendshipsRepository,
                               IMessagesRepository messagesRepository) {
        this.conversationsRepository = conversationsRepository;
        this.conversationParticipantsRepository = conversationParticipantsRepository;
        this.chatroomsRepository = chatroomsRepository;
        this.friendshipsRepository = friendshipsRepository;
        this.messagesRepository = messagesRepository;
    }

    @Transactional(readOnly = true)
    public boolean canAccess(long conversationId, String username) {
        return conversationId >= 0 && username != null
                && conversationParticipantsRepository.existsByConversationIdAndUserUsername(conversationId, username);
    }

    @Transactional(readOnly = true)
    public GetConversationsRsp getConversations(String username, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("page 必须非负，size 必须在 1–100 之间，分页偏移不能超过整数范围");
        }
        var result = conversationsRepository.findForUser(username, PageRequest.of(page, size));
        if (result.isEmpty()) {
            return new GetConversationsRsp(java.util.List.of(), page, size,
                    result.getTotalElements(), result.getTotalPages(), false);
        }

        var conversations = result.getContent().stream().map(this::toConversationInfo).toList();
        return new GetConversationsRsp(conversations, page, size, result.getTotalElements(),
                result.getTotalPages(), result.hasNext());
    }

    /** Converts a persisted conversation using the current user's title, mute and unread state. */
    @Transactional(readOnly = true)
    public ConversationInfo toConversationInfo(Conversation conversation) {
        if (conversation == null || conversation.getId() == null) {
            throw new IllegalArgumentException("会话必须是已持久化的实体");
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException("请先登录");
        }
        var username = authentication.getName();
        var id = conversation.getId();
        var ids = List.of(id);
        var participant = conversationParticipantsRepository.findForUserAndConversations(username, ids)
                .stream().findFirst().orElseThrow(() -> new AccessDeniedException("无权访问此会话"));
        var title = "会话 " + id;
        var type = ConversationType.Unknown;
        var avatarUrl = "";
        for (var friendship : friendshipsRepository.findForUserAndConversations(username, ids)) {
            var peer = friendship.getUserA().getUsername().equals(username)
                    ? friendship.getUserB() : friendship.getUserA();
            title = peer.getDisplayName();
            type = ConversationType.Friend;
            avatarUrl = GravatarUtils.getAvatarUrl(peer.getEmail());
        }
        for (var chatroom : chatroomsRepository.findByConversationIdIn(ids)) {
            if (chatroom.getName() != null && !chatroom.getName().isBlank()) title = chatroom.getName();
            type = ConversationType.Chatroom;
        }
        var hasNewMessage = !messagesRepository.findUnreadConversationIds(username, ids).isEmpty();
        return new ConversationInfo(id, title, hasNewMessage, participant.isMuted(), type, avatarUrl);
    }

    @Transactional(readOnly = true)
    public Optional<ConversationInfo> getConversationInfoById(Long id) {
        var conversation = conversationsRepository.findById(id);
        return conversation.map(this::toConversationInfo);
    }

    @Transactional
    public Conversation createConversation() {
        var conversation = new Conversation();
        conversationsRepository.save(conversation);
        return conversation;
    }

    @Transactional
    public ConversationParticipant addParticipant(Conversation conversation, User user) {
        var existing = conversationParticipantsRepository.findFirstByConversationIdAndUserId(
                conversation.getId(), user.getId());
        if (existing.isPresent()) return existing.get();
        var conversationParticipant = new ConversationParticipant();
        conversationParticipant.setConversation(conversation);
        conversationParticipant.setUser(user);
        return conversationParticipantsRepository.save(conversationParticipant);
    }
}
