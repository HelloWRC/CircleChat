package dev.hellowrc.circlechat;

import dev.hellowrc.circlechat.model.dto.UserInfo;
import dev.hellowrc.circlechat.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:websocket-proxy",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class WebSocketProxyTests {
    private static final String PUBLIC_HOST = "circle-chat-demo-fed90df.classisland.tech";
    private static final String PUBLIC_ORIGIN = "https://" + PUBLIC_HOST;

    @LocalServerPort
    private int port;

    @MockitoBean
    private AuthenticationManager authenticationManager;

    @MockitoBean
    private UserService userService;

    private String sessionCookie;

    @BeforeEach
    void login() throws Exception {
        when(authenticationManager.authenticate(any(Authentication.class))).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(
                        "alice", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
                )
        );
        when(userService.getUserInfoByUsername("alice")).thenReturn(
                new UserInfo(1L, "alice", "Alice", "alice@example.com", null, null, null, null, null)
        );
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                            + "/api/v1/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"username":"alice","password":"secret"}
                            """))
                    .build(), HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(200);
            sessionCookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        }
    }

    @Test
    void connectsAndReceivesStompConnectedBehindHttpsProxy() throws Exception {
        var connected = new CompletableFuture<String>();
        var listener = new WebSocket.Listener() {
            private final StringBuilder frame = new StringBuilder();

            @Override
            public java.util.concurrent.CompletionStage<?> onText(WebSocket socket, CharSequence data,
                                                                 boolean last) {
                frame.append(data);
                if (last) {
                    connected.complete(frame.toString());
                }
                socket.request(1);
                return null;
            }
        };
        try (var client = HttpClient.newHttpClient()) {
            var socket = proxiedSocket(client, PUBLIC_ORIGIN)
                    .header("Cookie", sessionCookie)
                    .buildAsync(socketUri(), listener).get(10, TimeUnit.SECONDS);
            try {
                socket.sendText("CONNECT\naccept-version:1.2\nhost:" + PUBLIC_HOST
                        + "\nheart-beat:0,0\n\n\0", true).get(10, TimeUnit.SECONDS);
                assertThat(connected.get(10, TimeUnit.SECONDS)).startsWith("CONNECTED\n");
            } finally {
                socket.abort();
            }
        }
    }

    @Test
    void broadcastsMessagesToSenderAndAnotherConnectionBehindHttpsProxy() throws Exception {
        try (var client = HttpClient.newHttpClient();
             var sender = connectAndSubscribe(client);
             var receiver = connectAndSubscribe(client)) {
            // The receiver's readiness message was also broadcast to the sender.
            assertThat(sender.frames().await("MESSAGE")).contains("ready");
            sender.send("{\"message\":\"线上消息测试\",\"clientMessageId\":\"sent-1\"}");
            assertThat(sender.frames().await("MESSAGE"))
                    .contains("subscription:room", "线上消息测试", "Alice", "alice");
            assertThat(sender.frames().await("MESSAGE"))
                    .contains("subscription:acks", "\"clientMessageId\":\"sent-1\"", "\"success\":true");
            assertThat(receiver.frames().await("MESSAGE"))
                    .contains("subscription:room", "线上消息测试", "Alice", "alice");
            // Acknowledgements must only go to the sending socket, even for the same logged-in user.
            assertThat(receiver.frames().frames.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void resubscribedConnectionsReceiveMessagesAndAcknowledgementsAfterReconnect() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (int attempt = 0; attempt < 3; attempt++) {
                try (var connection = connectAndSubscribe(client)) {
                    connection.send("{\"message\":\"after reconnect " + attempt
                            + "\",\"clientMessageId\":\"reconnect-" + attempt + "\"}");
                    assertThat(connection.frames().await("MESSAGE"))
                            .contains("subscription:room", "after reconnect " + attempt);
                    assertThat(connection.frames().await("MESSAGE"))
                            .contains("subscription:acks", "reconnect-" + attempt, "\"success\":true");
                }
            }
        }
    }

    @Test
    void rejectsBlankMessagesAndReportsServerFailureWithoutBroadcasting() throws Exception {
        try (var client = HttpClient.newHttpClient(); var connection = connectAndSubscribe(client)) {
            connection.send("{\"message\":\"  \",\"clientMessageId\":\"blank\"}");
            assertThat(connection.frames().await("MESSAGE"))
                    .contains("subscription:acks", "\"clientMessageId\":\"blank\"", "\"success\":false");
            when(userService.getUserInfoByUsername("alice")).thenThrow(new IllegalStateException("database unavailable"));
            connection.send("{\"message\":\"not delivered\",\"clientMessageId\":\"failure\"}");
            assertThat(connection.frames().await("MESSAGE"))
                    .contains("subscription:acks", "\"clientMessageId\":\"failure\"", "\"success\":false");
            assertThat(connection.frames().frames.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void negotiatesAndSendsHeartbeatWhileChatIsIdle() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var frames = new StompFrames();
            var socket = proxiedSocket(client, PUBLIC_ORIGIN).header("Cookie", sessionCookie)
                    .buildAsync(socketUri(), frames).get(10, TimeUnit.SECONDS);
            try {
                socket.sendText("CONNECT\naccept-version:1.2\nhost:" + PUBLIC_HOST
                        + "\nheart-beat:10000,10000\n\n\0", true).get(10, TimeUnit.SECONDS);
                assertThat(frames.await("CONNECTED")).contains("heart-beat:10000,10000");
                assertThat(frames.heartbeat.get(25, TimeUnit.SECONDS)).isTrue();
                assertThat(socket.isInputClosed()).isFalse();
            } finally {
                socket.abort();
            }
        }
    }

    private ChatConnection connectAndSubscribe(HttpClient client) throws Exception {
        var frames = new StompFrames();
        var socket = proxiedSocket(client, PUBLIC_ORIGIN).header("Cookie", sessionCookie)
                .buildAsync(socketUri(), frames).get(10, TimeUnit.SECONDS);
        var connection = new ChatConnection(socket, frames);
        try {
            socket.sendText("CONNECT\naccept-version:1.2\nhost:" + PUBLIC_HOST
                    + "\nheart-beat:0,0\n\n\0", true).get(10, TimeUnit.SECONDS);
            frames.await("CONNECTED");
            socket.sendText("SUBSCRIBE\nid:room\ndestination:/topic/chat/main\n\n\0", true)
                    .get(10, TimeUnit.SECONDS);
            socket.sendText("SUBSCRIBE\nid:acks\ndestination:/user/queue/chat/acks\n\n\0", true)
                    .get(10, TimeUnit.SECONDS);
            // Immediate SEND verifies inbound ordering: both SUBSCRIBEs must be processed first.
            connection.send("{\"message\":\"ready\",\"clientMessageId\":\"ready\"}");
            assertThat(frames.await("MESSAGE")).contains("subscription:room", "ready");
            assertThat(frames.await("MESSAGE")).contains("subscription:acks", "\"success\":true");
            return connection;
        } catch (Exception | AssertionError error) {
            connection.close();
            throw error;
        }
    }

    private record ChatConnection(WebSocket socket, StompFrames frames) implements AutoCloseable {
        void send(String body) throws Exception {
            socket.sendText("SEND\ndestination:/app/message/send\ncontent-type:application/json\n\n"
                    + body + "\0", true).get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            socket.abort();
        }
    }

    private static class StompFrames implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();
        private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final CompletableFuture<Boolean> heartbeat = new CompletableFuture<>();

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket socket, CharSequence data,
                                                             boolean last) {
            partial.append(data);
            if (partial.toString().equals("\n")) {
                heartbeat.complete(true);
                partial.setLength(0);
            }
            int end;
            while ((end = partial.indexOf("\0")) >= 0) {
                frames.add(partial.substring(0, end).stripLeading());
                partial.delete(0, end + 1);
            }
            socket.request(1);
            return null;
        }

        String await(String command) throws Exception {
            var frame = frames.poll(10, TimeUnit.SECONDS);
            assertThat(frame).as("Expected STOMP %s frame", command).isNotNull().startsWith(command + "\n");
            return frame;
        }
    }

    @Test
    void acceptsStandardForwardedHeader() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var socket = client.newWebSocketBuilder()
                    .header("Origin", PUBLIC_ORIGIN)
                    .header("Forwarded", "proto=https;host=" + PUBLIC_HOST)
                    .header("Cookie", sessionCookie)
                    .buildAsync(socketUri(), new WebSocket.Listener() {}).get(10, TimeUnit.SECONDS);
            socket.abort();
        }
    }

    @Test
    void stillAllowsLocalDevelopmentOrigin() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var socket = client.newWebSocketBuilder()
                    .header("Origin", "http://localhost:5173")
                    .header("Cookie", sessionCookie)
                    .buildAsync(socketUri(), new WebSocket.Listener() {}).get(10, TimeUnit.SECONDS);
            socket.abort();
        }
    }

    @Test
    void rejectsForeignOriginEvenWithAuthenticatedSession() {
        try (var client = HttpClient.newHttpClient()) {
            assertForbidden(proxiedSocket(client, "https://untrusted.example")
                    .header("Cookie", sessionCookie));
        }
    }

    @Test
    void rejectsAnonymousWebSocketHandshake() {
        try (var client = HttpClient.newHttpClient()) {
            assertForbidden(proxiedSocket(client, PUBLIC_ORIGIN));
        }
    }

    private WebSocket.Builder proxiedSocket(HttpClient client, String origin) {
        return client.newWebSocketBuilder()
                .header("Origin", origin)
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", PUBLIC_HOST)
                .header("X-Forwarded-Port", "443");
    }

    private URI socketUri() {
        return URI.create("ws://localhost:" + port + "/ws");
    }

    private void assertForbidden(WebSocket.Builder builder) {
        assertThatThrownBy(() -> {
            var socket = builder.buildAsync(socketUri(), new WebSocket.Listener() {})
                    .get(10, TimeUnit.SECONDS);
            socket.abort();
        })
                .hasCauseInstanceOf(WebSocketHandshakeException.class)
                .satisfies(error -> assertThat(((WebSocketHandshakeException) error.getCause())
                        .getResponse().statusCode()).isEqualTo(403));
    }
}
