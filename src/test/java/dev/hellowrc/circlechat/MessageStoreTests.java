package dev.hellowrc.circlechat;

import dev.hellowrc.circlechat.configuration.JpaAuditingConfig;
import dev.hellowrc.circlechat.model.dto.*;
import dev.hellowrc.circlechat.model.entitiy.User;
import dev.hellowrc.circlechat.model.entitiy.UserRole;
import dev.hellowrc.circlechat.repository.*;
import dev.hellowrc.circlechat.service.MessageService;
import dev.hellowrc.circlechat.service.MessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import jakarta.persistence.EntityManager;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:message-store",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({MessageStoreTests.StoreConfiguration.class, JpaAuditingConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MessageStoreTests {
    @TestConfiguration
    static class StoreConfiguration {
        @Bean
        @Primary
        BlockingMessageStore blockingMessageStore(IMessagesRepository repository, EntityManager entityManager,
                                                  IConversationsRepository conversations) {
            return new BlockingMessageStore(repository, entityManager, conversations);
        }
    }

    static class BlockingMessageStore extends MessageStore {
        volatile CountDownLatch committing;
        volatile CountDownLatch commitAllowed;

        BlockingMessageStore(IMessagesRepository repository, EntityManager entityManager,
                             IConversationsRepository conversations) {
            super(repository, entityManager, conversations);
        }

        public void blockCommit(CountDownLatch committing, CountDownLatch commitAllowed) {
            this.committing = committing;
            this.commitAllowed = commitAllowed;
        }

        @Override
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void write(ChatMessage message) {
            super.write(message);
            if (committing == null) return;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    committing.countDown();
                    try {
                        if (!commitAllowed.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Commit test timed out");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }
            });
        }
    }

    @Autowired private BlockingMessageStore store;
    @Autowired private IMessagesRepository messages;
    @Autowired private IUsersRepository users;
    @Autowired private JdbcTemplate jdbc;
    private User sender;

    @BeforeEach
    void prepare() {
        store.blockCommit(null, null);
        messages.deleteAll();
        users.deleteAll();
        jdbc.update("delete from conversations");
        jdbc.update("insert into conversations (id) values (0), (1)");
        sender = new User();
        sender.setUsername("alice"); sender.setDisplayName("Alice");
        sender.setEmail("alice@example.com"); sender.setPasswordHash("test"); sender.setRole(UserRole.User);
        sender = users.saveAndFlush(sender);
    }

    private ChatMessage message(String key, LocalDateTime time, String body) {
        return new ChatMessage(key, 0L, body, "Alice", "alice", sender.getId(), null, time);
    }

    @Test
    void commitPreservesUuidSendTimeLongBodyAndRetryIsIdempotent() {
        var dto = message(UUID.randomUUID().toString(), LocalDateTime.of(2026, 1, 1, 12, 0, 0, 123456000), "长消息".repeat(1000));
        store.write(dto);
        store.write(dto);
        assertThat(messages.count()).isEqualTo(1);
        var persisted = messages.findAll().getFirst();
        assertThat(persisted.getId()).isPositive();
        assertThat(persisted.getMessageKey()).isEqualTo(dto.id());
        assertThat(persisted.getSentAt()).isEqualTo(dto.sendTime());
        assertThat(persisted.getCreatedAt()).isNotNull();
        var read = store.read(0, null, null, store.latest(0), 50).getFirst();
        assertThat(read.id()).isEqualTo(dto.id());
        assertThat(read.body()).isEqualTo(dto.body());
        assertThat(read.sendTime()).isEqualTo(dto.sendTime());
        assertThat(read.senderUsername()).isEqualTo("alice");
    }

    @Test
    void cursorQueriesHandleEqualTimestampsAndExcludeOtherConversations() {
        var time = LocalDateTime.of(2026, 1, 1, 12, 0);
        var one = message("00000000-0000-0000-0000-000000000001", time, "one");
        var two = message("00000000-0000-0000-0000-000000000002", time, "two");
        var three = message("00000000-0000-0000-0000-000000000003", time, "three");
        store.write(one); store.write(two); store.write(three);
        store.write(new ChatMessage(UUID.randomUUID().toString(), 1L, "other", "Alice", "alice", sender.getId(), null, time.plusDays(1)));
        assertThat(store.read(0, null, null, store.latest(0), 2)).extracting(ChatMessage::body).containsExactly("three", "two");
        assertThat(store.read(0, MessageCursor.of(two), null, store.latest(0), 2)).extracting(ChatMessage::body).containsExactly("one");
        assertThat(store.read(0, null, MessageCursor.of(one), MessageCursor.of(two), 2)).extracting(ChatMessage::body).containsExactly("two");
    }

    @Test
    void newServiceRestoresHistoryAndUsesPersistedTimeAsOrderingBoundary() {
        var time = LocalDateTime.of(2090, 1, 1, 0, 0);
        var dto = message(UUID.randomUUID().toString(), time, "before restart");
        store.write(dto);
        var service = new MessageService(store, mock(SimpMessagingTemplate.class), 10, 1000, 10, 20);
        service.start();
        try {
            assertThat(service.history(0, null, null, null, 50).messages()).extracting(ChatMessage::id).containsExactly(dto.id());
            var user = new UserInfo(sender.getId(), "alice", "Alice", "alice@example.com", null, null, null, null, null);
            assertThat(service.send(0, "after restart", user).sendTime()).isAfter(time);
        } finally { service.stop(); }
        assertThat(messages.count()).isEqualTo(2);
    }

    @Test
    void failedTransactionDoesNotLeavePartialMessage() {
        var invalid = new ChatMessage(UUID.randomUUID().toString(), 0L, "invalid sender", "X", "x", -1L, null, LocalDateTime.now());
        assertThatThrownBy(() -> store.write(invalid)).isInstanceOf(RuntimeException.class);
        assertThat(messages.count()).isZero();
    }

    @Test
    void flushedButUncommittedMessageRemainsVisibleInHistoryAndConsumesCapacity() throws Exception {
        var committing = new CountDownLatch(1);
        var commitAllowed = new CountDownLatch(1);
        store.blockCommit(committing, commitAllowed);
        var service = new MessageService(store, mock(SimpMessagingTemplate.class), 1, 1000, 10, 20);
        service.start();
        var user = new UserInfo(sender.getId(), "alice", "Alice", "alice@example.com", null, null, null, null, null);
        try {
            var message = service.send(0, "waiting for commit", user);
            assertThat(committing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(messages.count()).isZero();
            assertThat(service.history(0, null, null, null, 50).messages()).containsExactly(message);
            assertThatThrownBy(() -> service.send(0, "too early", user)).hasMessageContaining("队列已满");
        } finally {
            commitAllowed.countDown();
            service.stop();
        }
        assertThat(messages.count()).isEqualTo(1);
        assertThat(service.history(0, null, null, null, 50).messages()).hasSize(1);
    }
}
