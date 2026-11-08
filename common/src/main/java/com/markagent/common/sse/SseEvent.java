package com.markagent.common.sse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * SSE 事件统一信封。seq 单调递增，作为断线回放锚点（GET /tasks/{id}/events?afterSeq=）。
 *
 * @param seq    事件序号（每任务独立自增）
 * @param type   事件类型
 * @param ts     时间戳（ISO-8601）
 * @param taskId 所属任务
 * @param data   类型化载荷（随 type 变化）
 */
public record SseEvent(long seq, SseEventType type, String ts, String taskId, JsonNode data) {
}
