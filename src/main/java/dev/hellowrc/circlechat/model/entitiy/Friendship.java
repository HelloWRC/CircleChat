package dev.hellowrc.circlechat.model.entitiy;

import dev.hellowrc.circlechat.abstraction.model.entity.EntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "friendships")
public class Friendship extends EntityBase {
    @ManyToOne
    @JoinColumn(name = "user_a_id", nullable = false)
    private User userA;

    @ManyToOne
    @JoinColumn(name = "user_b_id", nullable = false)
    private User userB;

    @OneToOne
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    public User getUserA() {
        return userA;
    }

    public void setUserA(User userA) {
        this.userA = userA;
    }

    public User getUserB() {
        return userB;
    }

    public void setUserB(User userB) {
        this.userB = userB;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
    }
}
