package dev.hellowrc.circlechat.service;

import dev.hellowrc.circlechat.model.dto.ChatMessage;
import dev.hellowrc.circlechat.model.dto.MessageCursor;
import dev.hellowrc.circlechat.model.dto.UserInfo;
import dev.hellowrc.circlechat.model.dto.responses.MessageHistoryRsp;
import dev.hellowrc.circlechat.model.dto.responses.ReceiveChatMessageRsp;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class MessageService {
    public static final long MAIN_CONVERSATION_ID = 0;
    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    private final Object gate = new Object();
    private final Map<String, ChatMessage> pending = new LinkedHashMap<>();
    private final LinkedBlockingQueue<ChatMessage> queue = new LinkedBlockingQueue<>();
    private final Semaphore capacity;
    private final MessageStore store;
    private final SimpMessagingTemplate messaging;
    private final long shutdownMillis;
    private final long initialRetryMillis;
    private final long maxRetryMillis;
    private volatile boolean accepting;
    private volatile boolean terminated;
    private MessageCursor boundary;
    private Thread worker;

    public MessageService(MessageStore store, SimpMessagingTemplate messaging,
                          @Value("${chat.persistence.capacity:10000}") int capacity,
                          @Value("${chat.persistence.shutdown-millis:30000}") long shutdownMillis,
                          @Value("${chat.persistence.retry-millis:1000}") long initialRetryMillis,
                          @Value("${chat.persistence.max-retry-millis:30000}") long maxRetryMillis) {
        if (capacity < 1 || shutdownMillis < 0 || initialRetryMillis < 1 || maxRetryMillis < initialRetryMillis)
            throw new IllegalArgumentException("Invalid chat persistence configuration");
        this.store = store;
        this.messaging = messaging;
        this.capacity = new Semaphore(capacity);
        this.shutdownMillis = shutdownMillis;
        this.initialRetryMillis = initialRetryMillis;
        this.maxRetryMillis = maxRetryMillis;
    }

    @PostConstruct
    public void start() {
        boundary = store.latest(MAIN_CONVERSATION_ID);
        accepting = true;
        worker = new Thread(this::consume, "chat-message-writer");
        worker.setDaemon(true);
        worker.start();
    }

    public static void requireConversation(long conversationId) {
        if (conversationId != MAIN_CONVERSATION_ID) throw new IllegalArgumentException("会话不存在");
    }

    public ChatMessage send(long conversationId, String body, UserInfo user) {
        requireConversation(conversationId);
        if (body == null || body.isBlank()) throw new IllegalArgumentException("消息不能为空");
        if (!capacity.tryAcquire()) throw new IllegalArgumentException("服务器消息队列已满，请稍后重试");
        synchronized (gate) {
            if (!accepting) {
                capacity.release();
                throw new IllegalArgumentException("服务器正在停止，请稍后重试");
            }
            var now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            if (!now.isAfter(boundary.time())) now = boundary.time().plus(1, ChronoUnit.MICROS);
            var message = new ChatMessage(UUID.randomUUID().toString(), conversationId, body,
                    user.displayName(), user.username(), user.id(), user.avatarSmallUrl(), now);
            pending.put(message.id(), message);
            try {
                messaging.convertAndSend("/topic/conversations/" + conversationId + "/messages",
                        new ReceiveChatMessageRsp(message));
                // The reserved slot makes enqueue infallible under ordinary operation.
                queue.add(message);
                boundary = MessageCursor.of(message);
                return message;
            } catch (RuntimeException exception) {
                pending.remove(message.id());
                capacity.release();
                throw exception;
            }
        }
    }

    public MessageHistoryRsp history(long conversationId, String beforeValue, String afterValue,
                                     String untilValue, int limit) {
        requireConversation(conversationId);
        if (limit < 1 || limit > 100 || (beforeValue != null && afterValue != null))
            throw new IllegalArgumentException("分页参数无效");
        var before = MessageCursor.decode(beforeValue, conversationId);
        var after = MessageCursor.decode(afterValue, conversationId);
        var until = MessageCursor.decode(untilValue, conversationId);
        final List<ChatMessage> snapshot;
        final MessageCursor upper;
        synchronized (gate) {
            // Copy BEFORE opening the read transaction: a committed-and-removed message
            // must either be in this snapshot or visible to the subsequent DB query.
            snapshot = List.copyOf(pending.values());
            upper = until == null || until.compareTo(boundary) > 0 ? boundary : until;
        }
        var merged = new HashMap<String, ChatMessage>();
        store.read(conversationId, before, after, upper, limit + 1).forEach(m -> merged.put(m.id(), m));
        snapshot.stream().filter(m -> {
            var cursor = MessageCursor.of(m);
            return m.conversationId() == conversationId && cursor.compareTo(upper) <= 0
                    && (before == null || cursor.compareTo(before) < 0)
                    && (after == null || cursor.compareTo(after) > 0);
        }).forEach(m -> merged.put(m.id(), m));
        Comparator<ChatMessage> comparator = Comparator.comparing(MessageCursor::of);
        var candidates = merged.values().stream().sorted(after == null ? comparator.reversed() : comparator)
                .limit(limit + 1L).toList();
        boolean hasMore = candidates.size() > limit;
        var page = new ArrayList<>(candidates.subList(0, Math.min(limit, candidates.size())));
        String nextCursor = hasMore ? MessageCursor.of(page.getLast()).encode() : null;
        page.sort(comparator);
        return new MessageHistoryRsp(List.copyOf(page), nextCursor, hasMore, upper.encode());
    }

    private void consume() {
        try {
            while (!terminated && (accepting || !queue.isEmpty())) {
                var message = queue.poll(250, TimeUnit.MILLISECONDS);
                if (message == null) continue;
                long retryMillis = initialRetryMillis;
                while (!terminated) {
                    try {
                        store.write(message);
                        synchronized (gate) {
                            pending.remove(message.id());
                            capacity.release();
                        }
                        break;
                    } catch (RuntimeException exception) {
                        log.error("Failed to persist message {}; retry in {} ms", message.id(), retryMillis, exception);
                        Thread.sleep(retryMillis);
                        retryMillis = Math.min(maxRetryMillis, retryMillis > maxRetryMillis / 2 ? maxRetryMillis : retryMillis * 2);
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void stop() {
        synchronized (gate) { accepting = false; }
        if (worker == null) return;
        try {
            if (shutdownMillis > 0) worker.join(shutdownMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            terminated = true;
            worker.interrupt();
            synchronized (gate) {
                if (!pending.isEmpty()) log.warn("Stopped with {} unpersisted chat messages", pending.size());
            }
        }
    }

    int pendingCount() {
        synchronized (gate) { return pending.size(); }
    }
}
