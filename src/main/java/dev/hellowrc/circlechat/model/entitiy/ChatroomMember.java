package dev.hellowrc.circlechat.model.entitiy;

import dev.hellowrc.circlechat.abstraction.model.entity.EntityBase;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "chatroom_members",
    indexes = {
        @Index(name = "index_user_id", columnList = "user_id"),
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "unique_chatroom_user", columnNames = {"chatroom_id", "user_id"})
    })
public class ChatroomMember extends EntityBase {
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "chatroom_id", nullable = false)
    private Chatroom chatroom;

    private boolean isAdmin;

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Chatroom getChatroom() {
        return chatroom;
    }

    public void setChatroom(Chatroom chatroom) {
        this.chatroom = chatroom;
    }

    public boolean isAdmin() {
        return isAdmin;
    }

    public void setAdmin(boolean admin) {
        isAdmin = admin;
    }
}
