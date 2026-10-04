package dev.hellowrc.circlechat;

import dev.hellowrc.circlechat.configuration.SecurityConfig;
import dev.hellowrc.circlechat.controller.AuthenticateController;
import dev.hellowrc.circlechat.controller.UsersController;
import dev.hellowrc.circlechat.model.dto.UserInfo;
import dev.hellowrc.circlechat.repository.IUsersRepository;
import dev.hellowrc.circlechat.service.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AuthenticateController.class, UsersController.class})
@Import({SecurityConfig.class, LogoutTests.TestSecurityConfiguration.class})
class LogoutTests {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestSecurityConfiguration {
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthenticationManager authenticationManager;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private IUsersRepository usersRepository;

    @BeforeEach
    void configureLogin() {
        when(authenticationManager.authenticate(any(Authentication.class))).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(
                        "alice", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
                )
        );
        when(userService.getUserInfoByUsername("alice")).thenReturn(
                new UserInfo(1L, "alice", "Alice", "alice@example.com", null, null, null, null, null)
        );
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"secret"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void logoutInvalidatesSessionClearsAuthenticationAndExpiresCookie() throws Exception {
        var session = login();
        var sessionCookie = new Cookie("JSESSIONID", session.getId());
        var context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
        );
        mvc.perform(get("/api/v1/users/me").session(session)).andExpect(status().isOk());

        var result = mvc.perform(post("/api/v1/auth/logout").session(session).cookie(sessionCookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("JSESSIONID", 0))
                .andExpect(cookie().path("JSESSIONID", "/"))
                .andReturn();

        assertThat(session.isInvalid()).isTrue();
        assertThat(context.getAuthentication()).isNull();
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        mvc.perform(get("/api/v1/users/me").cookie(sessionCookie))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void logoutWithoutSessionIsIdempotentAndStillExpiresCookie() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = mvc.perform(post("/api/v1/auth/logout"))
                    .andExpect(status().isNoContent())
                    .andExpect(cookie().maxAge("JSESSIONID", 0))
                    .andReturn();
            assertThat(result.getRequest().getSession(false)).isNull();
        }
    }

    @Test
    void getRequestDoesNotLogOutUser() throws Exception {
        var session = login();
        mvc.perform(get("/api/v1/auth/logout").session(session))
                .andExpect(status().is4xxClientError());
        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get("/api/v1/users/me").session(session)).andExpect(status().isOk());
    }
}
