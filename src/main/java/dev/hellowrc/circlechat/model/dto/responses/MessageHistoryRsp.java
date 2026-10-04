package dev.hellowrc.circlechat.model.dto.responses;

import dev.hellowrc.circlechat.model.dto.ChatMessage;
import java.util.List;

public record MessageHistoryRsp(List<ChatMessage> messages, String nextCursor,
                                boolean hasMore, String snapshotCursor) {}
