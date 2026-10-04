package dev.hellowrc.circlechat.model.entitiy;

import dev.hellowrc.circlechat.abstraction.model.entity.EntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "messages")
public class Message extends EntityBase {
    @Column(nullable = false)
    private String body;

    @ManyToOne
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public User getSender() {
        return sender;
    }

    public void setSender(User sender) {
        this.sender = sender;
    }
}
