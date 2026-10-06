package dev.hellowrc.circlechat;

import dev.hellowrc.circlechat.exception.ApiException;
import dev.hellowrc.circlechat.model.entitiy.FriendshipRequest;
import dev.hellowrc.circlechat.model.entitiy.FriendshipRequestState;
import dev.hellowrc.circlechat.model.entitiy.Message;
import dev.hellowrc.circlechat.model.entitiy.User;
import dev.hellowrc.circlechat.model.entitiy.UserRole;
import dev.hellowrc.circlechat.repository.IConversationParticipantsRepository;
import dev.hellowrc.circlechat.repository.IConversationsRepository;
import dev.hellowrc.circlechat.repository.IFriendshipRequestsRepository;
import dev.hellowrc.circlechat.repository.IFriendshipsRepository;
import dev.hellowrc.circlechat.repository.IMessagesRepository;
import dev.hellowrc.circlechat.repository.IUsersRepository;
import dev.hellowrc.circlechat.service.ConversationService;
import dev.hellowrc.circlechat.service.FriendsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:friends-api",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class FriendsApiTests {
    @Autowired private MockMvc mvc;
    @Autowired private IUsersRepository users;
    @Autowired private IFriendshipsRepository friendships;
    @Autowired private IFriendshipRequestsRepository requests;
    @Autowired private IConversationsRepository conversations;
    @Autowired private IConversationParticipantsRepository participants;
    @Autowired private IMessagesRepository messages;
    @Autowired private FriendsService friendsService;
    @Autowired private ConversationService conversationService;

    @BeforeEach
    void prepareUsers() {
        requests.deleteAllInBatch();
        friendships.deleteAllInBatch();
        messages.deleteAllInBatch();
        participants.deleteAllInBatch();
        conversations.findAll().stream().filter(conversation -> conversation.getId() != 0)
                .forEach(conversations::delete);
        for (var username : List.of("alice", "bob", "charlie")) {
            if (!users.existsByUsername(username)) {
                var user = new User();
                user.setUsername(username);
                user.setDisplayName(username.toUpperCase());
                user.setEmail(username + "@example.com");
                user.setPasswordHash("private-password-hash");
                user.setRole(UserRole.User);
                users.saveAndFlush(user);
            }
        }
    }

    @Test
    void sendsRequestsFromSessionAndReturnsOnlyPublicInformation() throws Exception {
        mvc.perform(authenticated("alice", post("/api/v1/friends/requests"))
                        .contentType("application/json")
                        .content("""
                                {"targetUsername":"bob","note":"你好","senderUsername":"charlie"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.content.id").isNumber())
                .andExpect(jsonPath("$.content.senderUsername").value("alice"))
                .andExpect(jsonPath("$.content.targetUsername").value("bob"))
                .andExpect(jsonPath("$.content.senderAvatarUrl").isString())
                .andExpect(jsonPath("$.content.note").value("你好"))
                .andExpect(jsonPath("$.content.state").value("Open"))
                .andExpect(jsonPath("$.content.createdAt").isString())
                .andExpect(jsonPath("$.content.sender.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.content.sender.email").doesNotExist());
        assertThat(requests.count()).isEqualTo(1);
        assertThat(friendships.count()).isZero();
        mvc.perform(authenticated("alice", get("/api/v1/friends/user/bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.username").value("bob"))
                .andExpect(jsonPath("$.content.isFriend").value(false))
                .andExpect(jsonPath("$.content.email").doesNotExist())
                .andExpect(jsonPath("$.content.passwordHash").doesNotExist());
        mvc.perform(authenticated("alice", get("/api/v1/friends/user/missing")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.statusCode").value(404));
    }

    @Test
    void acceptsOnceAndCreatesOneSharedConversationWithBothMemberships() throws Exception {
        var request = friendsService.sendFriendshipRequest("alice", "bob", null);
        mvc.perform(authenticated("bob", post("/api/v1/friends/requests/" + request.id() + "/accept")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.statusCode").value(200));
        assertThat(requests.findById(request.id()).orElseThrow().getState())
                .isEqualTo(FriendshipRequestState.Accepted);
        assertThat(friendships.count()).isEqualTo(1);
        assertThat(conversations.count()).isEqualTo(2);
        var conversationId = friendships.findAll().getFirst().getConversation().getId();
        assertThat(conversationService.canAccess(conversationId, "alice")).isTrue();
        assertThat(conversationService.canAccess(conversationId, "bob")).isTrue();
        assertThat(conversationService.canAccess(conversationId, "charlie")).isFalse();
        for (var username : List.of("alice", "bob")) {
            var peer = username.equals("alice") ? "bob" : "alice";
            mvc.perform(authenticated(username, get("/api/v1/friends/my")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.friends.length()").value(1))
                    .andExpect(jsonPath("$.content.friends[0].username").value(peer))
                    .andExpect(jsonPath("$.content.friends[0].isFriend").value(true));
            mvc.perform(authenticated(username, get("/api/v1/conversations")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.conversations[0].id").value(conversationId))
                    .andExpect(jsonPath("$.content.conversations[0].title").value(peer.toUpperCase()))
                    .andExpect(jsonPath("$.content.conversations[0].type").value("Friend"));
        }
        for (var action : List.of("accept", "reject", "ignore")) {
            mvc.perform(authenticated("bob", post("/api/v1/friends/requests/" + request.id() + "/" + action)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.statusCode").value(409));
        }
        assertThat(friendships.count()).isEqualTo(1);
        assertThat(conversations.count()).isEqualTo(2);
        mvc.perform(authenticated("alice", post("/api/v1/friends/requests"))
                        .contentType("application/json").content("{\"targetUsername\":\"bob\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void onlyRecipientCanProcessRequestsAndMissingIdsAreRejected() throws Exception {
        var request = friendsService.sendFriendshipRequest("alice", "bob", null);
        for (var username : List.of("alice", "charlie")) {
            for (var action : List.of("accept", "reject", "ignore")) {
                mvc.perform(authenticated(username,
                                post("/api/v1/friends/requests/" + request.id() + "/" + action)))
                        .andExpect(status().isNotFound()).andExpect(jsonPath("$.statusCode").value(404));
            }
        }
        for (var action : List.of("accept", "reject", "ignore")) {
            mvc.perform(authenticated("bob", post("/api/v1/friends/requests/9223372036854775807/" + action)))
                    .andExpect(status().isNotFound());
            mvc.perform(authenticated("bob", post("/api/v1/friends/requests/0/" + action)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(requests.findById(request.id()).orElseThrow().getState()).isEqualTo(FriendshipRequestState.Open);
        assertThat(friendships.count()).isZero();
        assertThat(conversations.count()).isEqualTo(1);
    }

    @Test
    void rejectsOrIgnoresWithoutCreatingFriendshipAndAllowsNewRequests() throws Exception {
        for (var action : List.of("reject", "ignore")) {
            var request = friendsService.sendFriendshipRequest("alice", "bob", null);
            mvc.perform(authenticated("bob", post("/api/v1/friends/requests/" + request.id() + "/" + action)))
                    .andExpect(status().isOk());
            assertThat(requests.findById(request.id()).orElseThrow().getState())
                    .isEqualTo(action.equals("reject") ? FriendshipRequestState.Rejected : FriendshipRequestState.Ignored);
            mvc.perform(authenticated("bob", post("/api/v1/friends/requests/" + request.id() + "/accept")))
                    .andExpect(status().isConflict());
        }
        assertThat(friendships.count()).isZero();
        assertThat(conversations.count()).isEqualTo(1);
    }

    @Test
    void paginatesReceivedAndSentRequestsAndFiltersByStateWithoutLeakingOthers() throws Exception {
        var first = friendsService.sendFriendshipRequest("alice", "bob", "first");
        var second = friendsService.sendFriendshipRequest("charlie", "bob", "second");
        friendsService.sendFriendshipRequest("alice", "charlie", "private");
        mvc.perform(authenticated("bob", get("/api/v1/friends/requests"))
                        .param("size", "1").param("username", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.content[0].id").value(second.id()))
                .andExpect(jsonPath("$.content.page").value(0))
                .andExpect(jsonPath("$.content.size").value(1))
                .andExpect(jsonPath("$.content.totalElements").value(2))
                .andExpect(jsonPath("$.content.totalPages").value(2))
                .andExpect(jsonPath("$.content.hasMore").value(true));
        mvc.perform(authenticated("bob", get("/api/v1/friends/requests")).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.content[0].id").value(first.id()))
                .andExpect(jsonPath("$.content.hasMore").value(false));
        mvc.perform(authenticated("alice", get("/api/v1/friends/requests")).param("sent", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.totalElements").value(2));
        mvc.perform(authenticated("bob", get("/api/v1/friends/requests")).param("sent", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.content").isEmpty());
        friendsService.rejectFriendshipRequest("bob", second.id(), false);
        mvc.perform(authenticated("bob", get("/api/v1/friends/requests")).param("state", "Open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.totalElements").value(1))
                .andExpect(jsonPath("$.content.content[0].id").value(first.id()));
        mvc.perform(authenticated("charlie", get("/api/v1/friends/requests"))
                        .param("sent", "true").param("state", "Rejected"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.content[0].id").value(second.id()));
        mvc.perform(authenticated("bob", get("/api/v1/friends/requests")).param("page", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.content").isEmpty())
                .andExpect(jsonPath("$.content.totalElements").value(2));
    }

    @Test
    void validatesRequestBodiesDuplicatesAndPagination() throws Exception {
        for (var body : List.of("{}", "{\"targetUsername\":\"\"}", "{\"targetUsername\":\"  \"}",
                "{\"targetUsername\":\"alice\"}", "{\"targetUsername\":\"" + "a".repeat(33) + "\"}",
                "{\"targetUsername\":\"bob\",\"note\":\"" + "x".repeat(256) + "\"}")) {
            mvc.perform(authenticated("alice", post("/api/v1/friends/requests"))
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.statusCode").value(400));
        }
        mvc.perform(authenticated("alice", post("/api/v1/friends/requests"))
                        .contentType("application/json").content("{\"targetUsername\":\"missing\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(authenticated("alice", post("/api/v1/friends/requests"))
                        .contentType("application/json").content("invalid"))
                .andExpect(status().isBadRequest());
        assertThat(requests.count()).isZero();
        friendsService.sendFriendshipRequest("alice", "bob", "x".repeat(255));
        for (var pair : List.of(List.of("alice", "bob"), List.of("bob", "alice"))) {
            mvc.perform(authenticated(pair.getFirst(), post("/api/v1/friends/requests"))
                            .contentType("application/json").content("{\"targetUsername\":\"" + pair.getLast() + "\"}"))
                    .andExpect(status().isConflict());
        }
        assertThat(requests.count()).isEqualTo(1);
        for (var page : List.of("-1", "invalid", "2147483647")) {
            mvc.perform(authenticated("alice", get("/api/v1/friends/requests")).param("page", page))
                    .andExpect(status().isBadRequest());
        }
        for (var size : List.of("0", "-1", "101", "invalid")) {
            mvc.perform(authenticated("alice", get("/api/v1/friends/requests")).param("size", size))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(authenticated("alice", get("/api/v1/friends/requests")).param("size", "100"))
                .andExpect(status().isOk());
        mvc.perform(authenticated("alice", get("/api/v1/friends/requests")).param("state", "invalid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void failedAcceptancePreservesOpenStateAndExistingConversation() throws Exception {
        var first = friendsService.sendFriendshipRequest("alice", "bob", null);
        friendsService.acceptFriendshipRequest("bob", first.id());
        // A legacy duplicate request must not create another friendship or change state on failure.
        var duplicate = new FriendshipRequest();
        duplicate.setSender(users.findByUsername("alice"));
        duplicate.setTarget(users.findByUsername("bob"));
        requests.saveAndFlush(duplicate);
        mvc.perform(authenticated("bob", post("/api/v1/friends/requests/" + duplicate.getId() + "/accept")))
                .andExpect(status().isConflict());
        assertThat(requests.findById(duplicate.getId()).orElseThrow().getState())
                .isEqualTo(FriendshipRequestState.Open);
        assertThat(friendships.count()).isEqualTo(1);
        assertThat(conversations.count()).isEqualTo(2);
    }

    @Test
    void deletesFriendshipFromEitherSideAndRevokesOnlyItsConversation() throws Exception {
        var request = friendsService.sendFriendshipRequest("alice", "bob", null);
        friendsService.acceptFriendshipRequest("bob", request.id());
        var conversationId = friendships.findAll().getFirst().getConversation().getId();
        var message = new Message();
        message.setConversation(conversations.findById(conversationId).orElseThrow());
        message.setSender(users.findByUsername("alice"));
        message.setBody("保留的聊天记录");
        messages.saveAndFlush(message);
        var other = conversationService.createConversation();
        conversationService.addParticipant(other, users.findByUsername("alice"));
        mvc.perform(authenticated("charlie", delete("/api/v1/friends/user/alice")))
                .andExpect(status().isNotFound());
        mvc.perform(authenticated("bob", delete("/api/v1/friends/user/alice")))
                .andExpect(status().isOk());
        assertThat(friendships.count()).isZero();
        assertThat(conversations.existsById(conversationId)).isTrue();
        assertThat(messages.findById(message.getId()).orElseThrow().getBody()).isEqualTo("保留的聊天记录");
        assertThat(conversationService.canAccess(conversationId, "alice")).isFalse();
        assertThat(conversationService.canAccess(conversationId, "bob")).isFalse();
        assertThat(conversationService.canAccess(other.getId(), "alice")).isTrue();
        for (var username : List.of("alice", "bob")) {
            mvc.perform(authenticated(username, get("/api/v1/conversations/" + conversationId + "/messages")))
                    .andExpect(status().isNotFound());
            mvc.perform(authenticated(username, get("/api/v1/friends/my")))
                    .andExpect(jsonPath("$.content.friends").isEmpty());
        }
        mvc.perform(authenticated("bob", delete("/api/v1/friends/user/alice")))
                .andExpect(status().isNotFound());
        mvc.perform(authenticated("alice", delete("/api/v1/friends/user/alice")))
                .andExpect(status().isBadRequest());
        var newRequest = friendsService.sendFriendshipRequest("alice", "bob", null);
        friendsService.acceptFriendshipRequest("bob", newRequest.id());
        mvc.perform(authenticated("alice", delete("/api/v1/friends/user/bob")))
                .andExpect(status().isOk());
        assertThat(friendships.count()).isZero();
    }

    @Test
    void allFriendOperationsRequireAuthentication() throws Exception {
        for (var url : List.of("/api/v1/friends/my", "/api/v1/friends/user/bob", "/api/v1/friends/requests")) {
            mvc.perform(get(url)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/friends/requests").contentType("application/json")
                        .content("{\"targetUsername\":\"bob\"}"))
                .andExpect(status().isForbidden());
        for (var action : List.of("accept", "reject", "ignore")) {
            mvc.perform(post("/api/v1/friends/requests/1/" + action)).andExpect(status().isForbidden());
        }
        mvc.perform(delete("/api/v1/friends/user/bob")).andExpect(status().isForbidden());
    }

    @Test
    void concurrentRequestsCreateOnlyOneOpenRequest() throws Exception {
        var statuses = runConcurrently(
                () -> friendsService.sendFriendshipRequest("alice", "bob", null),
                () -> friendsService.sendFriendshipRequest("bob", "alice", null));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    void concurrentAcceptancesCreateOnlyOneFriendshipAndConversation() throws Exception {
        var request = friendsService.sendFriendshipRequest("alice", "bob", null);
        var statuses = runConcurrently(
                () -> friendsService.acceptFriendshipRequest("bob", request.id()),
                () -> friendsService.acceptFriendshipRequest("bob", request.id()));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(friendships.count()).isEqualTo(1);
        assertThat(conversations.count()).isEqualTo(2);
        assertThat(participants.count()).isEqualTo(2);
        assertThat(requests.findById(request.id()).orElseThrow().getState())
                .isEqualTo(FriendshipRequestState.Accepted);
    }

    private List<Integer> runConcurrently(Runnable firstAction, Runnable secondAction) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(concurrentAction(start, firstAction));
            var second = executor.submit(concurrentAction(start, secondAction));
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }

    private Callable<Integer> concurrentAction(CountDownLatch start, Runnable action) {
        return () -> {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                action.run();
                return 200;
            } catch (ApiException exception) {
                return exception.getStatusCode();
            }
        };
    }

    private MockHttpServletRequestBuilder authenticated(String username, MockHttpServletRequestBuilder request) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return request.session(session);
    }
}
