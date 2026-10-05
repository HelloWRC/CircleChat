package dev.hellowrc.circlechat.model.entitiy;

import dev.hellowrc.circlechat.abstraction.model.entity.EntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "chatrooms")
public class Chatroom extends EntityBase {
    @ManyToOne
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    private String name;

    @OneToOne
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    public User getOwner() {
        return owner;
    }

    public void setOwner(User owner) {
        this.owner = owner;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
    }
}
