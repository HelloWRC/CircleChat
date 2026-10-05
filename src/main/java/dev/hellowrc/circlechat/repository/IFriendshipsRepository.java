package dev.hellowrc.circlechat.repository;

import dev.hellowrc.circlechat.model.entitiy.Friendship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface IFriendshipsRepository extends JpaRepository<Friendship, Long> {
    @EntityGraph(attributePaths = {"conversation", "userA", "userB"})
    @Query("""
            select f from Friendship f where f.conversation.id in :conversationIds
            and (f.userA.username = :username or f.userB.username = :username)
            """)
    List<Friendship> findForUserAndConversations(String username, Collection<Long> conversationIds);
}
