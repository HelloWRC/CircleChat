package dev.hellowrc.circlechat.controller;

import dev.hellowrc.circlechat.model.dto.ChatMessage;
import dev.hellowrc.circlechat.model.dto.requests.SendChatMessageReq;
import dev.hellowrc.circlechat.model.dto.responses.ReceiveChatMessageRsp;
import dev.hellowrc.circlechat.model.dto.responses.SendChatMessageRsp;
import dev.hellowrc.circlechat.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.LocalDateTime;

@Controller
public class MessagesController {
    private static final Logger log = LoggerFactory.getLogger(MessagesController.class);
    private final SimpMessagingTemplate messagingTemplate;
    private final UserService userService;

    public MessagesController(SimpMessagingTemplate messagingTemplate, UserService userService) {
        this.messagingTemplate = messagingTemplate;
        this.userService = userService;
    }

    @MessageMapping("message/send")
    @SendToUser(value = "/queue/chat/acks", broadcast = false)
    public SendChatMessageRsp send(SendChatMessageReq req, Principal principal) {
        if (principal == null) {
            return new SendChatMessageRsp(req.clientMessageId(), false, "登录已失效，请重新登录");
        }
        if (req.message() == null || req.message().isBlank()) {
            return new SendChatMessageRsp(req.clientMessageId(), false, "消息不能为空");
        }
        try {
            var user = userService.getUserInfoByUsername(principal.getName());

            messagingTemplate.convertAndSend("/topic/chat/main", new ReceiveChatMessageRsp(new ChatMessage(
                    0L,  // TODO: 到时候存数据库里，把数据库 id 弄下来
                    req.message(),
                    user.displayName(),
                    user.username(),
                    user.id(),
                    user.avatarSmallUrl(),
                    LocalDateTime.now()
            )));
            return new SendChatMessageRsp(req.clientMessageId(), true, null);
        } catch (RuntimeException exception) {
            log.error("Failed to broadcast chat message for user {}", principal.getName(), exception);
            return new SendChatMessageRsp(req.clientMessageId(), false, "服务器未能发送消息，请稍后重试");
        }
    }

}
