package dev.hellowrc.circlechat;

import dev.hellowrc.circlechat.repository.IUsersRepository;
import dev.hellowrc.circlechat.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user-settings",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class UserSettingsTests {
    @Autowired private MockMvc mvc;
    @Autowired private IUsersRepository users;
    @Autowired private UserService userService;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void prepareUsers() {
        for (var username : new String[]{"alice", "bob"}) {
            if (!users.existsByUsername(username)) {
                userService.createUser(username, username + "@example.com", username, "old-password");
            }
            var user = users.findByUsername(username);
            user.setDisplayName(username);
            user.setPasswordHash(passwordEncoder.encode("old-password"));
            users.saveAndFlush(user);
        }
    }

    private MockHttpSession login(String password) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpServletRequestBuilder passwordRequest(MockHttpSession session, String body) {
        return put("/api/v1/users/me/password").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void requiresLoginForBothUpdates() throws Exception {
        mvc.perform(patch("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Changed\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/users/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old-password\",\"newPassword\":\"new\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updatesOnlyAuthenticatedProfileAndReturnsFreshPublicInformation() throws Exception {
        var session = login("old-password");
        var previousHash = users.findByUsername("alice").getPasswordHash();
        mvc.perform(patch("/api/v1/users/me").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"  新名称  ","username":"bob","email":"changed@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.user.displayName").value("新名称"))
                .andExpect(jsonPath("$.content.user.username").value("alice"))
                .andExpect(jsonPath("$.content.user.email").value("alice@example.com"))
                .andExpect(jsonPath("$.content.user.avatarLargeUrl").isString())
                .andExpect(jsonPath("$.content.user.updatedAt").isString())
                .andExpect(jsonPath("$.content.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.content.user.password").doesNotExist());
        assertThat(users.findByUsername("alice").getDisplayName()).isEqualTo("新名称");
        assertThat(users.findByUsername("alice").getPasswordHash()).isEqualTo(previousHash);
        assertThat(users.findByUsername("bob").getDisplayName()).isEqualTo("bob");
        mvc.perform(get("/api/v1/users/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.user.displayName").value("新名称"));
    }

    @Test
    void validatesProfileNameAndAcceptsMaximumLength() throws Exception {
        var session = login("old-password");
        for (var body : new String[]{"{}", "{\"displayName\":null}", "{\"displayName\":\"\"}",
                "{\"displayName\":\"   \"}", "{\"displayName\":\"" + "名".repeat(65) + "\"}"}) {
            mvc.perform(patch("/api/v1/users/me").session(session).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.statusCode").value(400));
            assertThat(users.findByUsername("alice").getDisplayName()).isEqualTo("alice");
        }
        mvc.perform(patch("/api/v1/users/me").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"  " + "名".repeat(64) + "  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.user.displayName").value("名".repeat(64)));
    }

    @Test
    void passwordChangeKeepsSessionsAndChangesOnlyOwnEncodedPassword() throws Exception {
        var session = login("old-password");
        var anotherSession = login("old-password");
        mvc.perform(passwordRequest(session, """
                        {"currentPassword":"old-password","newPassword":" x ","username":"bob"}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.statusCode").value(200));
        var hash = users.findByUsername("alice").getPasswordHash();
        assertThat(hash).isNotEqualTo(" x ");
        assertThat(passwordEncoder.matches(" x ", hash)).isTrue();
        assertThat(passwordEncoder.matches("x", hash)).isFalse();
        assertThat(passwordEncoder.matches("old-password", hash)).isFalse();
        assertThat(passwordEncoder.matches("old-password", users.findByUsername("bob").getPasswordHash())).isTrue();
        assertThat(users.findByUsername("alice").getDisplayName()).isEqualTo("alice");
        for (var existingSession : new MockHttpSession[]{session, anotherSession}) {
            mvc.perform(get("/api/v1/users/me").session(existingSession)).andExpect(status().isOk());
            assertThat(existingSession.isInvalid()).isFalse();
        }
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"old-password\"}"))
                .andExpect(status().isForbidden());
        login(" x ");
    }

    @Test
    void invalidPasswordRequestsLeaveStoredHashUnchanged() throws Exception {
        var session = login("old-password");
        var hash = users.findByUsername("alice").getPasswordHash();
        for (var body : new String[]{"{}", "{\"currentPassword\":null,\"newPassword\":\"x\"}",
                "{\"currentPassword\":\"\",\"newPassword\":\"x\"}",
                "{\"currentPassword\":\"wrong\",\"newPassword\":\"x\"}",
                "{\"currentPassword\":\"old-password\",\"newPassword\":null}",
                "{\"currentPassword\":\"old-password\",\"newPassword\":\"\"}",
                "{\"currentPassword\":\"old-password\",\"newPassword\":\"" + "x".repeat(73) + "\"}",
                "{\"currentPassword\":\"old-password\",\"newPassword\":\"" + "密".repeat(25) + "\"}"}) {
            mvc.perform(passwordRequest(session, body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.statusCode").value(400)).andExpect(jsonPath("$.message").isString());
            assertThat(users.findByUsername("alice").getPasswordHash()).isEqualTo(hash);
        }
    }

    @Test
    void acceptsSingleCharacterAndSeventyTwoUtf8Bytes() throws Exception {
        var session = login("old-password");
        mvc.perform(passwordRequest(session, "{\"currentPassword\":\"old-password\",\"newPassword\":\"x\"}"))
                .andExpect(status().isOk());
        mvc.perform(passwordRequest(session,
                        "{\"currentPassword\":\"x\",\"newPassword\":\"" + "密".repeat(24) + "\"}"))
                .andExpect(status().isOk());
        assertThat(passwordEncoder.matches("密".repeat(24), users.findByUsername("alice").getPasswordHash())).isTrue();
    }

    @Test
    void concurrentProfileAndPasswordChangesPreserveBothFields() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var profile = executor.submit(() -> {
                start.await();
                userService.updateDisplayName("alice", "Updated");
                return null;
            });
            var password = executor.submit(() -> {
                start.await();
                userService.changePassword("alice", "old-password", "new-password");
                return null;
            });
            start.countDown();
            profile.get(10, TimeUnit.SECONDS);
            password.get(10, TimeUnit.SECONDS);
        }
        var user = users.findByUsername("alice");
        assertThat(user.getDisplayName()).isEqualTo("Updated");
        assertThat(passwordEncoder.matches("new-password", user.getPasswordHash())).isTrue();
    }
}
