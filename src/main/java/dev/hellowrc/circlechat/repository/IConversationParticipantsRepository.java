package dev.hellowrc.circlechat.repository;

import dev.hellowrc.circlechat.model.entitiy.ConversationParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IConversationParticipantsRepository extends JpaRepository<ConversationParticipant, Long> {
    boolean existsByConversationIdAndUserUsername(Long conversationId, String username);
    Optional<ConversationParticipant> findFirstByConversationIdAndUserId(Long conversationId, Long userId);

    @Query("""
            select p from ConversationParticipant p join fetch p.conversation
            where p.user.username = :username and p.conversation.id in :conversationIds
            """)
    List<ConversationParticipant> findForUserAndConversations(String username, Collection<Long> conversationIds);
}
