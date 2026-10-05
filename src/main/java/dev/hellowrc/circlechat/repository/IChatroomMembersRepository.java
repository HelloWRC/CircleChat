package dev.hellowrc.circlechat.repository;

import dev.hellowrc.circlechat.model.entitiy.ChatroomMember;
import dev.hellowrc.circlechat.model.entitiy.Chatroom;
import dev.hellowrc.circlechat.model.entitiy.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IChatroomMembersRepository extends JpaRepository<ChatroomMember, Long> {
    Optional<ChatroomMember> findByChatroomAndUser(Chatroom chatroom, User user);
}
