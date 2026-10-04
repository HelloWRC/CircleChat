package dev.hellowrc.circlechat;

import org.junit.jupiter.api.Test;
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
    void historyRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/conversations/0/messages")).andExpect(status().isForbidden());
    }

    @Test
    void exportsActualOpenApiContractForFrontendGeneration() throws Exception {
        var result = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ChatMessage.properties.id.type").value("string"))
                .andExpect(jsonPath("$.components.schemas.ChatMessage.properties.conversationId.type").value("integer"))
                .andReturn();
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/openapi.json"), result.getResponse().getContentAsString());
    }
}
