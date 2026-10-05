package dev.hellowrc.circlechat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import dev.hellowrc.circlechat.repository.IUsersRepository;
import dev.hellowrc.circlechat.repository.IConversationsRepository;
import dev.hellowrc.circlechat.service.UserService;
import dev.hellowrc.circlechat.service.ConversationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:message-api",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class MessageApiTests {
    @Autowired private MockMvc mvc;
    @Autowired private IUsersRepository users;
    @Autowired private IConversationsRepository conversations;
    @Autowired private UserService userService;
    @Autowired private ConversationService conversationService;

    @BeforeEach
    void joinMainConversation() {
        if (!users.existsByUsername("alice")) userService.createUser("alice", "alice@example.com", "Alice", "secret");
        conversationService.addParticipant(conversations.findById(0L).orElseThrow(), users.findByUsername("alice"));
    }

    private static MockHttpServletRequestBuilder authenticatedGet(String url) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "alice", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return get(url).session(session);
    }

    @Test
    void readsEmptyMainConversationAndRejectsUnsupportedIdsAndInvalidPagination() throws Exception {
        mvc.perform(authenticatedGet("/api/v1/conversations/0/messages"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.messages").isEmpty())
                .andExpect(jsonPath("$.content.hasMore").value(false))
                .andExpect(jsonPath("$.content.snapshotCursor").isString());
        mvc.perform(authenticatedGet("/api/v1/conversations/1/messages")).andExpect(status().isNotFound());
        mvc.perform(authenticatedGet("/api/v1/conversations/-1/messages")).andExpect(status().isNotFound());
        mvc.perform(authenticatedGet("/api/v1/conversations/0/messages").param("before", "invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(authenticatedGet("/api/v1/conversations/0/messages").param("before", "x").param("after", "y"))
                .andExpect(status().isBadRequest());
        mvc.perform(authenticatedGet("/api/v1/conversations/0/messages").param("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(authenticatedGet("/api/v1/conversations/0/messages").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readsConversationMetadataAndRejectsMissingOrUnauthorizedConversations() throws Exception {
        mvc.perform(authenticatedGet("/api/v1/conversations/0/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.info.id").value(0))
                .andExpect(jsonPath("$.content.info.title").value("主聊天室"))
                .andExpect(jsonPath("$.content.info.type").value("Chatroom"));
        mvc.perform(authenticatedGet("/api/v1/conversations/9223372036854775807/meta"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/conversations/0/meta")).andExpect(status().isForbidden());
        var privateConversation = conversationService.createConversation();
        mvc.perform(authenticatedGet("/api/v1/conversations/" + privateConversation.getId() + "/meta"))
                .andExpect(status().isForbidden());
    }

    @Test
    void historyRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/conversations/0/messages")).andExpect(status().isForbidden());
    }

    @Test
    void exportsActualOpenApiContractForFrontendGeneration() throws Exception {
        var result = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ChatMessage.properties.id.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.ChatMessage.properties.conversationId.type").value("integer"))
                .andExpect(jsonPath("$.paths['/api/v1/conversations'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/conversations/{id}/meta'].get").exists())
                .andExpect(jsonPath("$.components.schemas.GetConversationsRsp.properties.totalElements.type").value("integer"))
                .andReturn();
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/openapi.json"), result.getResponse().getContentAsString());
    }
}
