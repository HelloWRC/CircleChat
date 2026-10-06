package dev.hellowrc.circlechat.repository;

import dev.hellowrc.circlechat.model.entitiy.Conversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface IConversationsRepository extends JpaRepository<Conversation, Long> {
    @Query("""
            select c from Conversation c
            where exists (select p.id from ConversationParticipant p
                          where p.conversation = c and p.user.username = :username)
            order by c.id desc
            """)
    Page<Conversation> findForUser(String username, Pageable pageable);

}
