package dev.hellowrc.circlechat.repository;

import dev.hellowrc.circlechat.model.entitiy.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface IMessagesRepository extends JpaRepository<Message, Long> {
    boolean existsByMessageKey(String messageKey);
    Optional<Message> findFirstByConversationIdOrderBySentAtDescMessageKeyDesc(Long conversationId);
}
