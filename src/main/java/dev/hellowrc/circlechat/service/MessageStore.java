package dev.hellowrc.circlechat.service;

import dev.hellowrc.circlechat.model.dto.ChatMessage;
import dev.hellowrc.circlechat.model.dto.MessageCursor;
import dev.hellowrc.circlechat.model.entitiy.Message;
import dev.hellowrc.circlechat.model.entitiy.User;
import dev.hellowrc.circlechat.repository.IMessagesRepository;
import dev.hellowrc.circlechat.utils.GravatarUtils;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Invoked through a separate bean so write() returns only after transaction commit. */
@Service
public class MessageStore {
    private final IMessagesRepository repository;
    private final EntityManager entityManager;

    public MessageStore(IMessagesRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public MessageCursor latest(long conversationId) {
        return repository.findFirstByConversationIdOrderBySentAtDescMessageKeyDesc(conversationId)
                .map(m -> new MessageCursor(conversationId, m.getSentAt(), m.getMessageKey()))
                .orElseGet(() -> MessageCursor.empty(conversationId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(ChatMessage dto) {
        // An earlier attempt may have committed even if the caller saw a connection error.
        if (repository.existsByMessageKey(dto.id())) return;
        var message = new Message();
        message.setMessageKey(dto.id());
        message.setConversationId(dto.conversationId());
        message.setSentAt(dto.sendTime());
        message.setBody(dto.body());
        message.setSender(entityManager.getReference(User.class, dto.senderId()));
        repository.saveAndFlush(message);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public List<ChatMessage> read(long conversationId, MessageCursor before, MessageCursor after,
                                  MessageCursor upper, int count) {
        var jpql = new StringBuilder("select m from Message m join fetch m.sender where m.conversationId = :conversationId");
        jpql.append(" and (m.sentAt < :upperTime or (m.sentAt = :upperTime and m.messageKey <= :upperKey))");
        if (before != null) jpql.append(" and (m.sentAt < :beforeTime or (m.sentAt = :beforeTime and m.messageKey < :beforeKey))");
        if (after != null) jpql.append(" and (m.sentAt > :afterTime or (m.sentAt = :afterTime and m.messageKey > :afterKey))");
        jpql.append(after == null ? " order by m.sentAt desc, m.messageKey desc" : " order by m.sentAt asc, m.messageKey asc");
        var query = entityManager.createQuery(jpql.toString(), Message.class)
                .setParameter("conversationId", conversationId)
                .setParameter("upperTime", upper.time()).setParameter("upperKey", upper.key());
        if (before != null) query.setParameter("beforeTime", before.time()).setParameter("beforeKey", before.key());
        if (after != null) query.setParameter("afterTime", after.time()).setParameter("afterKey", after.key());
        return query.setMaxResults(count).getResultList().stream().map(m -> {
            var sender = m.getSender();
            return new ChatMessage(m.getMessageKey(), m.getConversationId(), m.getBody(),
                    sender.getDisplayName(), sender.getUsername(), sender.getId(),
                    GravatarUtils.getAvatarUrl(sender.getEmail(), GravatarUtils.SmallAvatarSize), m.getSentAt());
        }).toList();
    }
}
