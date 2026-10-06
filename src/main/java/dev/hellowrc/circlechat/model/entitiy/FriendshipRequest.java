package dev.hellowrc.circlechat.model.entitiy;

import dev.hellowrc.circlechat.abstraction.model.entity.EntityBase;
import jakarta.persistence.*;

@Entity
@Table( name = "friendship_requests", indexes = {
    @Index( name = "idx_sender_id", columnList = "sender_id"),
    @Index( name = "idx_target_id", columnList = "target_id"),
    @Index( name = "idx_state", columnList = "state")
})
public class FriendshipRequest extends EntityBase {
    @ManyToOne
    @JoinColumn( name = "sender_id", nullable = false)
    private User sender;

    @ManyToOne
    @JoinColumn( name = "target_id", nullable = false)
    private User target;

    private String note;

    @Enumerated(EnumType.STRING)
    private FriendshipRequestState state = FriendshipRequestState.Open;

    public User getSender() {
        return sender;
    }

    public void setSender(User sender) {
        this.sender = sender;
    }

    public User getTarget() {
        return target;
    }

    public void setTarget(User target) {
        this.target = target;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public FriendshipRequestState getState() {
        return state;
    }

    public void setState(FriendshipRequestState state) {
        this.state = state;
    }
}
