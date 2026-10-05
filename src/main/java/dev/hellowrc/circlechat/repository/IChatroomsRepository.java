package dev.hellowrc.circlechat.repository;

import dev.hellowrc.circlechat.model.entitiy.Chatroom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.Collection;
import java.util.List;

public interface IChatroomsRepository extends JpaRepository<Chatroom, Long> {
    @EntityGraph(attributePaths = {"conversation", "owner"})
    List<Chatroom> findByConversationIdIn(Collection<Long> conversationIds);
}
