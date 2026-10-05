package dev.hellowrc.circlechat.model.entitiy;

import dev.hellowrc.circlechat.abstraction.model.entity.EntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "conversation_participants", indexes = {
        @Index(name = "idx_participants_user_conversation", columnList = "user_id,conversation_id")
})
public class ConversationParticipant extends EntityBase {
    @ManyToOne
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private String lastReadMessageKey = "00000000-0000-0000-0000-000000000000";

    private boolean muted;

    private boolean pinned;

    public Conversation getConversation() {
        return conversation;
    }

    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getLastReadMessageKey() {
        return lastReadMessageKey;
    }

    public void setLastReadMessageKey(String lastReadMessageKey) {
        this.lastReadMessageKey = lastReadMessageKey;
    }

    public boolean isMuted() {
        return muted;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }
}
