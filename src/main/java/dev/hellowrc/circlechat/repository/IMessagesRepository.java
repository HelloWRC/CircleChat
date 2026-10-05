package dev.hellowrc.circlechat.repository;

import dev.hellowrc.circlechat.model.entitiy.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IMessagesRepository extends JpaRepository<Message, Long> {
    boolean existsByMessageKey(String messageKey);
    Optional<Message> findFirstByConversationIdOrderBySentAtDescMessageKeyDesc(Long conversationId);

    // UUIDs are identifiers; unread ordering uses sentAt and UUID together, just like history.
    @Query("""
            select distinct m.conversation.id from Message m
            where m.conversation.id in :conversationIds
            and exists (select p.id from ConversationParticipant p
                        where p.conversation = m.conversation and p.user.username = :username
                        and not exists (select r.id from Message r
                                        where r.conversation = m.conversation
                                        and r.messageKey = p.lastReadMessageKey
                                        and (r.sentAt > m.sentAt or
                                             (r.sentAt = m.sentAt and r.messageKey >= m.messageKey))))
            """)
    List<Long> findUnreadConversationIds(String username, Collection<Long> conversationIds);
}
