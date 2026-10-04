package dev.hellowrc.circlechat.controller;

import dev.hellowrc.circlechat.model.dto.ChatMessage;
import dev.hellowrc.circlechat.model.dto.requests.SendChatMessageReq;
import dev.hellowrc.circlechat.model.dto.responses.ReceiveChatMessageRsp;
import dev.hellowrc.circlechat.service.UserService;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.LocalDateTime;

@Controller
public class MessagesController {
    private final SimpMessagingTemplate messagingTemplate;
    private final UserService userService;

    public MessagesController(SimpMessagingTemplate messagingTemplate, UserService userService) {
        this.messagingTemplate = messagingTemplate;
        this.userService = userService;
    }

    @MessageMapping("message/send")
    public void send(SendChatMessageReq req, Principal principal) {
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
    }

}
