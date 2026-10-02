package dev.hellowrc.circlechat.controller;

import dev.hellowrc.circlechat.model.dto.requests.SendChatMessageReq;
import dev.hellowrc.circlechat.model.dto.responses.ReceiveChatMessageRsp;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
public class MessagesController {
    private final SimpMessagingTemplate messagingTemplate;

    public MessagesController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("message/send")
    public void send(SendChatMessageReq req) {
        messagingTemplate.convertAndSend("/topic/chat/main", new ReceiveChatMessageRsp(req.message(), "test"));
    }

}
