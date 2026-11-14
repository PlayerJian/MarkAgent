package com.markagent.inbox;

import java.time.Instant;

/**
 * 会话信箱消息（data/inbox/{userId}/{sessionId}.jsonl 的单行 schema）。
 * seq 为信箱内单调递增序号（1 起始，由 {@link SessionInboxService#enqueue} 按行数分配），
 * 作为投递游标（.cursor 文件）的去重锚点。
 */
public record InboxMessage(
        long seq,
        String fromUserId,
        String fromTaskId,
        String content,
        Instant createdAt
) {
}
