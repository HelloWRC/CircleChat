package dev.hellowrc.circlechat.configuration;

import dev.hellowrc.circlechat.service.ConversationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final ObjectProvider<MessageChannel> outbound;
    private final ConversationService conversations;

    public WebSocketConfig(@Qualifier("clientOutboundChannel") ObjectProvider<MessageChannel> outbound,
                           ConversationService conversations) {
        this.outbound = outbound;
        this.conversations = conversations;
    }

    private Message<?> reject(StompHeaderAccessor request, String error) {
        // Ordered inbound channels absorb interceptor exceptions. Send the protocol
        // error explicitly to this session; the STOMP handler closes it after ERROR.
        var response = StompHeaderAccessor.create(StompCommand.ERROR);
        response.setSessionId(request.getSessionId());
        response.setMessage(error);
        outbound.getObject().send(MessageBuilder.createMessage(new byte[0], response.getMessageHeaders()));
        return null;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                var headers = StompHeaderAccessor.wrap(message);
                var destination = headers.getDestination();
                if (destination == null) return message;
                if (headers.getCommand() == StompCommand.SEND &&
                        (destination.startsWith("/topic/") || destination.startsWith("/queue/")
                                || destination.startsWith("/user/"))) {
                    // A direct broker publish would bypass validation and persistence.
                    return reject(headers, "请通过会话发送接口发送消息");
                }
                if (headers.getCommand() == StompCommand.SUBSCRIBE &&
                        !canSubscribeToConversation(destination, headers) &&
                        !destination.equals("/user/queue/chat/acks") &&
                        !destination.equals("/user/queue/chat/ready")) {
                    return reject(headers, "订阅目标不存在");
                }
                return message;
            }
        });
    }

    private boolean canSubscribeToConversation(String destination, StompHeaderAccessor headers) {
        var prefix = "/topic/conversations/";
        var suffix = "/messages";
        if (!destination.startsWith(prefix) || !destination.endsWith(suffix) || headers.getUser() == null)
            return false;
        var id = destination.substring(prefix.length(), destination.length() - suffix.length());
        if (!id.matches("0|[1-9][0-9]*")) return false;
        try {
            return conversations.canAccess(Long.parseLong(id), headers.getUser().getName());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setPreserveReceiveOrder(true);
        registry.addEndpoint("/ws").setAllowedOriginPatterns(
                "http://localhost:5173",
                "http://127.0.0.1:5173"
        );
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setPreservePublishOrder(true);
        registry.enableSimpleBroker("/topic", "/queue")
                .setTaskScheduler(chatHeartbeatScheduler())
                .setHeartbeatValue(new long[]{10000, 10000});
    }

    @Bean
    public ThreadPoolTaskScheduler chatHeartbeatScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("chat-heartbeat-");
        return scheduler;
    }

}
