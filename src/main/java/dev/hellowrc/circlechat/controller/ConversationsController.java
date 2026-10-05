package dev.hellowrc.circlechat.controller;

import dev.hellowrc.circlechat.model.dto.responses.GetConversationMetaRsp;
import dev.hellowrc.circlechat.model.dto.responses.GetConversationsRsp;
import dev.hellowrc.circlechat.model.dto.responses.HttpRequestRsp;
import dev.hellowrc.circlechat.service.ConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationsController {
    private static final Logger log = LoggerFactory.getLogger(ConversationsController.class);
    private final ConversationService conversationService;

    public ConversationsController(ConversationService conversations) {
        this.conversationService = conversations;
    }

    @GetMapping({"", "/"})
    public HttpRequestRsp<GetConversationsRsp> getConversations(Principal principal,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        try {
            return new HttpRequestRsp<>(conversationService.getConversations(principal.getName(), page, size), 200, "ok");
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            log.error("Failed to query conversations for user {}", principal.getName(), exception);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法加载聊天会话", exception);
        }
    }

    @GetMapping("/{id}/meta")
    public HttpRequestRsp<GetConversationMetaRsp> getConversationMeta(@PathVariable Long id){
        var conversation = conversationService.getConversationInfoById(id);
        if (conversation.isPresent()) {
            return new HttpRequestRsp<>(new GetConversationMetaRsp(conversation.get()), 200, "ok");
        }

        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到对应的会话");
    }
}
