package dev.hellowrc.circlechat;

import dev.hellowrc.circlechat.model.entitiy.*;
import dev.hellowrc.circlechat.repository.*;
import dev.hellowrc.circlechat.service.ChatroomService;
import dev.hellowrc.circlechat.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:conversation-api",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@Transactional
class ConversationApiTests {
    @Autowired private MockMvc mvc;
    @Autowired private IUsersRepository users;
    @Autowired private IConversationsRepository conversations;
    @Autowired private IConversationParticipantsRepository participants;
    @Autowired private IChatroomsRepository chatrooms;
    @Autowired private IFriendshipsRepository friendships;
    @Autowired private IMessagesRepository messages;
    @Autowired private PasswordEncoder passwords;
    @Autowired private ConversationService conversationService;
    @Autowired private ChatroomService chatroomService;
    private User alice;
    private User bob;

    @BeforeEach
    void prepareUsers() {
        alice = user("alice", "Alice");
        bob = user("bob", "Bob");
    }

    @Test
    @WithMockUser(username = "alice")
    void convertsOneEntityUsingCurrentUsersMetadataAndRejectsNonMembers() {
        var conversation = conversationService.createConversation();
        var participant = conversationService.addParticipant(conversation, alice);
        participant.setMuted(true);
        participants.saveAndFlush(participant);
        conversationService.addParticipant(conversation, bob);
        var friendship = new Friendship();
        friendship.setConversation(conversation);
        friendship.setUserA(alice);
        friendship.setUserB(bob);
        friendships.saveAndFlush(friendship);

        var info = conversationService.toConversationInfo(conversation);
        assertThat(info).isEqualTo(conversationService.getConversations("alice", 0, 20).conversations().getFirst());
        assertThat(info.title()).isEqualTo("Bob");
        assertThat(info.isMuted()).isTrue();

        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "bob", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        info = conversationService.toConversationInfo(conversation);
        assertThat(info.title()).isEqualTo("Alice");
        assertThat(info.isMuted()).isFalse();
        var privateConversation = conversationService.createConversation();
        conversationService.addParticipant(privateConversation, alice);
        assertThatThrownBy(() -> conversationService.toConversationInfo(privateConversation))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void paginatesOnlyCurrentUsersConversationsWithMetadataAndStableOrder() throws Exception {
        var first = conversationService.createConversation();
        var participant = conversationService.addParticipant(first, alice);
        participant.setMuted(true);
        participants.saveAndFlush(participant);
        var friend = new Friendship();
        friend.setConversation(first); friend.setUserA(alice); friend.setUserB(bob);
        friendships.saveAndFlush(friend);

        var second = chatroomService.createChatroom(alice);
        second.setName("测试聊天室");
        chatrooms.saveAndFlush(second);
        var third = conversationService.createConversation();
        conversationService.addParticipant(third, alice);
        // Duplicate legacy membership rows must not duplicate conversations or inflate counts.
        var duplicate = new ConversationParticipant();
        duplicate.setConversation(third); duplicate.setUser(alice);
        participants.saveAndFlush(duplicate);
        var privateConversation = conversationService.createConversation();
        conversationService.addParticipant(privateConversation, bob);

        mvc.perform(authenticatedGet("alice").param("size", "2").param("username", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.content.conversations.length()").value(2))
                .andExpect(jsonPath("$.content.conversations[0].id").value(third.getId()))
                .andExpect(jsonPath("$.content.conversations[0].type").value("Unknown"))
                .andExpect(jsonPath("$.content.conversations[1].id").value(second.getConversation().getId()))
                .andExpect(jsonPath("$.content.conversations[1].title").value("测试聊天室"))
                .andExpect(jsonPath("$.content.conversations[1].type").value("Chatroom"))
                .andExpect(jsonPath("$.content.page").value(0))
                .andExpect(jsonPath("$.content.size").value(2))
                .andExpect(jsonPath("$.content.totalElements").value(3))
                .andExpect(jsonPath("$.content.totalPages").value(2))
                .andExpect(jsonPath("$.content.hasMore").value(true));
        mvc.perform(authenticatedGet("alice").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.conversations.length()").value(1))
                .andExpect(jsonPath("$.content.conversations[0].id").value(first.getId()))
                .andExpect(jsonPath("$.content.conversations[0].title").value("Bob"))
                .andExpect(jsonPath("$.content.conversations[0].type").value("Friend"))
                .andExpect(jsonPath("$.content.conversations[0].isMuted").value(true))
                .andExpect(jsonPath("$.content.conversations[0].hasNewMessage").value(false))
                .andExpect(jsonPath("$.content.hasMore").value(false));
        conversationService.addParticipant(first, bob);
        mvc.perform(authenticatedGet("bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.conversations[1].title").value("Alice"));
    }

    @Test
    void unreadUsesMessageTimeAndUuidTieBreakerWithinTheConversation() throws Exception {
        var conversation = conversationService.createConversation();
        var participant = conversationService.addParticipant(conversation, alice);
        var time = LocalDateTime.of(2026, 1, 1, 12, 0);
        var older = message(conversation, time, "00000000-0000-0000-0000-000000000009");
        mvc.perform(authenticatedGet("alice"))
                .andExpect(jsonPath("$.content.conversations[0].hasNewMessage").value(true));
        participant.setLastReadMessageKey(older.getMessageKey());
        participants.saveAndFlush(participant);
        mvc.perform(authenticatedGet("alice"))
                .andExpect(jsonPath("$.content.conversations[0].hasNewMessage").value(false));
        var later = message(conversation, time.plusSeconds(1), "00000000-0000-0000-0000-000000000001");
        mvc.perform(authenticatedGet("alice"))
                .andExpect(jsonPath("$.content.conversations[0].hasNewMessage").value(true));
        participant.setLastReadMessageKey(later.getMessageKey());
        participants.saveAndFlush(participant);
        message(conversation, time.plusSeconds(1), "00000000-0000-0000-0000-000000000002");
        mvc.perform(authenticatedGet("alice"))
                .andExpect(jsonPath("$.content.conversations[0].hasNewMessage").value(true));
        var other = conversationService.createConversation();
        var foreignRead = message(other, time.plusDays(1), "00000000-0000-0000-0000-000000000003");
        participant.setLastReadMessageKey(foreignRead.getMessageKey());
        participants.saveAndFlush(participant);
        mvc.perform(authenticatedGet("alice"))
                .andExpect(jsonPath("$.content.conversations[0].hasNewMessage").value(true));
    }

    @Test
    void supportsDefaultsEmptyAndOutOfRangePagesAndRejectsInvalidParameters() throws Exception {
        mvc.perform(authenticatedGet("alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.conversations").isEmpty())
                .andExpect(jsonPath("$.content.page").value(0))
                .andExpect(jsonPath("$.content.size").value(20))
                .andExpect(jsonPath("$.content.totalElements").value(0))
                .andExpect(jsonPath("$.content.totalPages").value(0))
                .andExpect(jsonPath("$.content.hasMore").value(false));
        conversationService.addParticipant(conversationService.createConversation(), alice);
        mvc.perform(authenticatedGet("alice").param("page", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.conversations").isEmpty())
                .andExpect(jsonPath("$.content.totalElements").value(1))
                .andExpect(jsonPath("$.content.hasMore").value(false));
        for (var value : List.of("-1", "invalid", "2147483647")) {
            mvc.perform(authenticatedGet("alice").param("page", value)).andExpect(status().isBadRequest());
        }
        for (var value : List.of("0", "-1", "101", "invalid")) {
            mvc.perform(authenticatedGet("alice").param("size", value)).andExpect(status().isBadRequest());
        }
        mvc.perform(authenticatedGet("alice").param("size", "100")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/conversations")).andExpect(status().isForbidden());
    }

    @Test
    void loginAddsMainConversationOnceEvenWithOtherChatroomMemberships() throws Exception {
        var other = chatroomService.createChatroom(alice);
        var main = chatrooms.findById(ChatroomService.MAIN_CHATROOM_ID).orElseThrow();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                            .content(""" 
                                    {"username":"alice","password":"secret"}
                                    """))
                    .andExpect(status().isOk());
        }
        assertThat(main.getConversation().getId()).isZero();
        assertThat(participants.findForUserAndConversations("alice",
                List.of(main.getConversation().getId(), other.getConversation().getId()))).hasSize(2);
        mvc.perform(authenticatedGet("alice"))
                .andExpect(jsonPath("$.content.totalElements").value(2))
                .andExpect(jsonPath("$.content.conversations[1].id").value(0))
                .andExpect(jsonPath("$.content.conversations[1].title").value("主聊天室"));
    }

    private MockHttpServletRequestBuilder authenticatedGet(String username) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return get("/api/v1/conversations").session(session);
    }

    private User user(String username, String displayName) {
        var user = new User();
        user.setUsername(username); user.setDisplayName(displayName);
        user.setPasswordHash(passwords.encode("secret"));
        user.setEmail(username + "@example.com"); user.setRole(UserRole.User);
        return users.saveAndFlush(user);
    }

    private Message message(Conversation conversation, LocalDateTime time, String key) {
        var message = new Message();
        message.setConversation(conversation); message.setSender(bob);
        message.setBody("message"); message.setSentAt(time); message.setMessageKey(key);
        return messages.saveAndFlush(message);
    }
}
