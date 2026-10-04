package dev.hellowrc.circlechat.controller;

import dev.hellowrc.circlechat.model.dto.requests.ChatReadyReq;
import dev.hellowrc.circlechat.model.dto.requests.SendChatMessageReq;
import dev.hellowrc.circlechat.model.dto.responses.*;
import dev.hellowrc.circlechat.service.MessageService;
import dev.hellowrc.circlechat.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;

@RestController
public class MessagesController {
    private static final Logger log = LoggerFactory.getLogger(MessagesController.class);
    private final MessageService messages;
    private final UserService userService;

    public MessagesController(MessageService messages, UserService userService) {
        this.messages = messages;
        this.userService = userService;
    }

    @MessageMapping("conversations/{conversationId}/messages/send")
    @SendToUser(value = "/queue/chat/acks", broadcast = false)
    public SendChatMessageRsp send(@DestinationVariable long conversationId, SendChatMessageReq req, Principal principal) {
        if (principal == null) {
            return new SendChatMessageRsp(req.clientMessageId(), false, "登录已失效，请重新登录");
        }
        if (req.message() == null || req.message().isBlank()) {
            return new SendChatMessageRsp(req.clientMessageId(), false, "消息不能为空");
        }
        try {
            MessageService.requireConversation(conversationId);
            var user = userService.getUserInfoByUsername(principal.getName());
            messages.send(conversationId, req.message(), user);
            return new SendChatMessageRsp(req.clientMessageId(), true, null);
        } catch (IllegalArgumentException exception) {
            return new SendChatMessageRsp(req.clientMessageId(), false, exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("Failed to broadcast chat message for user {}", principal.getName(), exception);
            return new SendChatMessageRsp(req.clientMessageId(), false, "服务器未能发送消息，请稍后重试");
        }
    }

    @MessageMapping("conversations/{conversationId}/ready")
    @SendToUser(value = "/queue/chat/ready", broadcast = false)
    public ChatReadyRsp ready(@DestinationVariable long conversationId, ChatReadyReq req, Principal principal) {
        String error = principal == null ? "登录已失效，请重新登录"
                : conversationId != MessageService.MAIN_CONVERSATION_ID ? "会话不存在" : null;
        return new ChatReadyRsp(req.requestId(), conversationId, error == null, error);
    }

    @GetMapping("/api/v1/conversations/{conversationId}/messages")
    public HttpRequestRsp<MessageHistoryRsp> history(@PathVariable long conversationId,
            @RequestParam(required = false) String before, @RequestParam(required = false) String after,
            @RequestParam(required = false) String until, @RequestParam(defaultValue = "50") int limit) {
        if (conversationId != MessageService.MAIN_CONVERSATION_ID)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        try {
            return new HttpRequestRsp<>(messages.history(conversationId, before, after, until, limit), 200, "ok");
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            log.error("Failed to query message history", exception);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法加载历史消息", exception);
        }
    }

}
