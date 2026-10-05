package dev.hellowrc.circlechat.service;

import dev.hellowrc.circlechat.model.entitiy.Chatroom;
import dev.hellowrc.circlechat.model.entitiy.ChatroomMember;
import dev.hellowrc.circlechat.model.entitiy.ConversationParticipant;
import dev.hellowrc.circlechat.model.entitiy.User;
import dev.hellowrc.circlechat.repository.IChatroomMembersRepository;
import dev.hellowrc.circlechat.repository.IChatroomsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ChatroomService {
    public static final Long MAIN_CHATROOM_ID = 0L;

    private final IChatroomsRepository chatroomsRepository;
    private final ConversationService conversationService;
    private final IChatroomMembersRepository chatroomMembersRepository;

    public ChatroomService(IChatroomsRepository chatroomsRepository,
                           ConversationService conversationService,
                           IChatroomMembersRepository chatroomMembersRepository) {

        this.chatroomsRepository = chatroomsRepository;
        this.conversationService = conversationService;
        this.chatroomMembersRepository = chatroomMembersRepository;
    }

    public Chatroom createChatroom(User owner) {
        var conversation = conversationService.createConversation();
        var chatroom = new Chatroom();
        chatroom.setConversation(conversation);
        chatroom.setOwner(owner);
        chatroomsRepository.save(chatroom);

        var membership = addChatroomMemberIfNotExists(chatroom, owner);
        membership.setAdmin(true);
        return chatroom;
    }

    public ChatroomMember addChatroomMemberIfNotExists(Chatroom chatroom, User member) {
        conversationService.addParticipant(chatroom.getConversation(), member);
        var rawMember = chatroomMembersRepository.findByChatroomAndUser(chatroom, member)
                .orElse(null);
        if (rawMember != null) {
            return rawMember;
        }
        var chatroomMember = new ChatroomMember();
        chatroomMember.setChatroom(chatroom);
        chatroomMember.setUser(member);
        chatroomMembersRepository.save(chatroomMember);
        return chatroomMember;
    }
}
