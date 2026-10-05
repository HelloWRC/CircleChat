package dev.hellowrc.circlechat;

import dev.hellowrc.circlechat.abstraction.model.entity.EntityBase;
import dev.hellowrc.circlechat.configuration.JpaAuditingConfig;
import dev.hellowrc.circlechat.model.entitiy.Message;
import dev.hellowrc.circlechat.model.entitiy.Conversation;
import dev.hellowrc.circlechat.model.entitiy.User;
import dev.hellowrc.circlechat.model.entitiy.UserRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.auditing.AuditingHandler;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auditing",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(JpaAuditingConfig.class)
class EntityAuditingTests {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AuditingHandler auditingHandler;

    private LocalDateTime currentTime;

    @BeforeEach
    void configureClock() {
        currentTime = LocalDateTime.of(2026, 1, 1, 12, 0);
        auditingHandler.setDateTimeProvider(() -> Optional.of(currentTime));
    }

    @Test
    void fillsUserAuditDatesOnInsertAndUpdate() {
        assertAuditLifecycle(newUser(), User.class, user -> user.setDisplayName("Updated name"));
    }

    @Test
    void fillsMessageAuditDatesOnInsertAndUpdate() {
        var sender = newUser();
        entityManager.persist(sender);

        var message = new Message();
        var conversation = new Conversation();
        entityManager.persist(conversation);
        message.setConversation(conversation);
        message.setSender(sender);
        message.setBody("Initial message");

        assertAuditLifecycle(message, Message.class, value -> value.setBody("Updated message"));
    }

    private <T extends EntityBase> void assertAuditLifecycle(T entity, Class<T> entityType,
                                                           Consumer<T> update) {
        var createdAt = currentTime;
        entityManager.persist(entity);
        entityManager.flush();
        entityManager.clear();

        var persisted = entityManager.find(entityType, entity.getId());
        assertThat(persisted.getCreatedAt()).isEqualTo(createdAt);
        assertThat(persisted.getUpdatedAt()).isEqualTo(createdAt);

        currentTime = createdAt.plusHours(1);
        update.accept(persisted);
        // Even an explicitly changed creation date must not overwrite the stored value.
        persisted.setCreatedAt(currentTime);
        entityManager.flush();
        entityManager.clear();

        var updated = entityManager.find(entityType, entity.getId());
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isEqualTo(currentTime);
    }

    private User newUser() {
        var user = new User();
        user.setUsername("audit-user");
        user.setPasswordHash("test-password-hash");
        user.setEmail("audit@example.com");
        user.setDisplayName("Audit user");
        user.setRole(UserRole.User);
        return user;
    }
}
