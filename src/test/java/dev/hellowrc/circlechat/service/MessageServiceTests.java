package dev.hellowrc.circlechat.service;

import dev.hellowrc.circlechat.model.dto.*;
import dev.hellowrc.circlechat.model.dto.responses.ReceiveChatMessageRsp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessageServiceTests {
    private final MessageStore store = mock(MessageStore.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final UserInfo user = new UserInfo(1L, "alice", "Alice", "alice@example.com", null, null, null, null, null);
    private MessageService service;

    private void start(int capacity) {
        when(store.latest(0)).thenReturn(MessageCursor.empty(0));
        when(store.read(anyLong(), any(), any(), any(), anyInt())).thenReturn(List.of());
        service = new MessageService(store, messaging, capacity, 1000, 10, 20);
        service.start();
    }

    @AfterEach
    void stop() { if (service != null) service.stop(); }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void latch(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void conversationBoundariesAndPendingHistoryStayIndependent() throws Exception {
        start(10);
        var future = LocalDateTime.of(2090, 1, 1, 12, 0);
        when(store.latest(7)).thenReturn(new MessageCursor(7, future, "00000000-0000-0000-0000-000000000001"));
        var release = new CountDownLatch(1);
        doAnswer(call -> { latch(release); return null; }).when(store).write(any());
        try {
            var other = service.send(7, "other room", user);
            var main = service.send(0, "main room", user);
            assertThat(other.sendTime()).isAfter(future);
            assertThat(main.sendTime()).isBefore(future);
            assertThat(service.history(7, null, null, null, 50).messages()).containsExactly(other);
            assertThat(service.history(0, null, null, null, 50).messages()).containsExactly(main);
            verify(messaging).convertAndSend(eq("/topic/conversations/7/messages"), any(Object.class));
        } finally { release.countDown(); }
    }

    @Test
    void broadcastsBeforeWritingAndHistoryIncludesInFlightMessageUntilCommit() throws Exception {
        start(1);
        var writing = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var durable = new AtomicReference<ChatMessage>();
        doAnswer(call -> { writing.countDown(); latch(commit); durable.set(call.getArgument(0)); return null; })
                .when(store).write(any());
        doAnswer(call -> {
            verify(store, never()).write(any());
            var dto = ((ReceiveChatMessageRsp) call.getArgument(1)).message();
            assertThat(dto.id()).isNotEqualTo("0");
            assertThat(dto.conversationId()).isZero();
            return null;
        }).when(messaging).convertAndSend(anyString(), any(Object.class));
        try {
            var message = service.send(0, "broadcast first", user);
            latch(writing);
            assertThat(service.history(0, null, null, null, 50).messages()).containsExactly(message);
            assertThatThrownBy(() -> service.send(0, "queue full", user)).hasMessageContaining("队列已满");
            verify(messaging, times(1)).convertAndSend(anyString(), any(Object.class));
            commit.countDown();
            await(() -> service.pendingCount() == 0);
            when(store.read(anyLong(), any(), any(), any(), anyInt())).thenReturn(List.of(durable.get()));
            assertThat(service.history(0, null, null, null, 50).messages()).containsExactly(message);
        } finally { commit.countDown(); }
    }

    @Test
    void copiedPendingSnapshotSurvivesCommitAndRemovalDuringDatabaseRead() throws Exception {
        start(2);
        var writing = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var reading = new CountDownLatch(1);
        var finishRead = new CountDownLatch(1);
        doAnswer(call -> { writing.countDown(); latch(commit); return null; }).when(store).write(any());
        var message = service.send(0, "racing commit", user);
        latch(writing);
        when(store.read(anyLong(), any(), any(), any(), anyInt())).thenAnswer(call -> {
            reading.countDown();
            latch(finishRead);
            // The query saw the database before commit, but returns after pending cleanup.
            return List.of();
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var history = executor.submit(() -> service.history(0, null, null, null, 50));
            try {
                latch(reading);
                commit.countDown();
                await(() -> service.pendingCount() == 0);
                finishRead.countDown();
                assertThat(history.get(5, TimeUnit.SECONDS).messages()).containsExactly(message);
            } finally { commit.countDown(); finishRead.countDown(); }
        }
    }

    @Test
    void mergesCommittedAndPendingCopiesOnlyOnce() throws Exception {
        start(2);
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> { writing.countDown(); latch(release); return null; }).when(store).write(any());
        try {
            var message = service.send(0, "overlap", user);
            latch(writing);
            when(store.read(anyLong(), any(), any(), any(), anyInt())).thenReturn(List.of(message));
            assertThat(service.history(0, null, null, null, 50).messages()).containsExactly(message);
        } finally { release.countDown(); }
    }

    @Test
    void retriesHeadWithoutSkippingLaterMessagesAndDrainsOnStop() throws Exception {
        start(3);
        var firstFailed = new CountDownLatch(1);
        var retry = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        var written = new CopyOnWriteArrayList<String>();
        doAnswer(call -> {
            ChatMessage message = call.getArgument(0);
            if (attempts.getAndIncrement() == 0) {
                firstFailed.countDown();
                latch(retry);
                throw new IllegalStateException("temporary database failure");
            }
            written.add(message.body());
            return null;
        }).when(store).write(any());
        try {
            service.send(0, "first", user);
            latch(firstFailed);
            service.send(0, "second", user);
            assertThat(service.history(0, null, null, null, 50).messages()).extracting(ChatMessage::body)
                    .containsExactly("first", "second");
            retry.countDown();
            service.stop();
            assertThat(written).containsExactly("first", "second");
            assertThat(service.pendingCount()).isZero();
            assertThatThrownBy(() -> service.send(0, "stopped", user)).hasMessageContaining("停止");
        } finally { retry.countDown(); }
    }

    @Test
    void validatesInputsAndBroadcastFailureReleasesReservedCapacity() {
        start(1);
        assertThatThrownBy(() -> service.send(-1, "invalid room", user)).hasMessage("会话不存在");
        assertThatThrownBy(() -> service.send(0, "  ", user)).hasMessage("消息不能为空");
        doThrow(new IllegalStateException("broadcast failure")).when(messaging).convertAndSend(anyString(), any(Object.class));
        assertThatThrownBy(() -> service.send(0, "one", user)).hasMessage("broadcast failure");
        assertThatThrownBy(() -> service.send(0, "two", user)).hasMessage("broadcast failure");
        assertThat(service.pendingCount()).isZero();
        verify(store, never()).write(any());
        assertThatThrownBy(() -> service.history(0, "bad", null, null, 50)).hasMessageContaining("游标");
        assertThatThrownBy(() -> service.history(0, null, null, null, 101)).hasMessageContaining("分页");
        assertThatThrownBy(() -> service.history(0, "x", "y", null, 50)).hasMessageContaining("分页");
        var other = new MessageCursor(1, LocalDateTime.now(), "00000000-0000-0000-0000-000000000001").encode();
        assertThatThrownBy(() -> service.history(0, other, null, null, 50)).hasMessageContaining("游标");
    }

    @Test
    void paginatesPendingMessagesAndFreezesCatchUpUpperBound() throws Exception {
        start(10);
        var release = new CountDownLatch(1);
        doAnswer(call -> { latch(release); return null; }).when(store).write(any());
        try {
            var one = service.send(0, "one", user);
            var two = service.send(0, "two", user);
            var three = service.send(0, "three", user);
            var latest = service.history(0, null, null, null, 2);
            assertThat(latest.messages()).containsExactly(two, three);
            assertThat(latest.hasMore()).isTrue();
            var older = service.history(0, latest.nextCursor(), null, latest.snapshotCursor(), 2);
            assertThat(older.messages()).containsExactly(one);
            assertThat(older.hasMore()).isFalse();
            var first = service.history(0, null, MessageCursor.of(one).encode(), null, 1);
            assertThat(first.messages()).containsExactly(two);
            service.send(0, "outside snapshot", user);
            var rest = service.history(0, null, first.nextCursor(), first.snapshotCursor(), 1);
            assertThat(rest.messages()).containsExactly(three);
            assertThat(rest.hasMore()).isFalse();
            assertThat(two.sendTime()).isAfter(one.sendTime());
            assertThat(three.sendTime().getNano() % 1000).isZero();
        } finally { release.countDown(); }
    }
}
