package com.markagent.common.sse;

/**
 * SSE 事件类型契约（server 推送、web/cli 消费、events/*.jsonl 落盘的统一枚举）。
 * 每条 SSE：id: &lt;seq&gt;（回放锚点）、event: &lt;type&gt;、data: 事件 JSON。
 */
public enum SseEventType {
    /** 任务元信息：sessionId/userId/model/provider/planning/workerRoles */
    META,
    /** 文本增量 {messageId, delta} */
    TEXT,
    /** 思考内容增量（思考模式开启时输出，前端折叠展示） {messageId, delta} */
    REASONING,
    /** 工具调用开始 {callId, name, args} */
    TOOL_START,
    /** 工具调用结束 {callId, name, ok, resultSummary} */
    TOOL_END,
    /** 计划树全量快照 {steps:[{id,title,status,detail}], currentStepId} */
    PLAN_UPDATE,
    /** worker 派生 {agentId, name, role, goal} */
    AGENT_SPAWN,
    /** agent 间消息 {fromId, fromName, toId, toName, content} */
    AGENT_MSG,
    /** 跨会话消息送达 {fromTaskId, sessionId, content} */
    SESSION_MSG,
    /** agent 完成 {agentId, summary} */
    AGENT_DONE,
    /** 记忆变更 {op, target} */
    MEMORY_UPDATE,
    /** 上下文使用量 {used, budget, percent} */
    TOKEN,
    /** 检查点写入 {checkpointId, stepIndex} */
    CHECKPOINT,
    /** 任务暂停 {checkpointId} */
    PAUSED,
    /** 人工审批请求 {callId, question, options, timeoutSeconds} */
    ASK_USER,
    /** 上下文压缩 {phase: planned|completed|failed, ...} */
    COMPACT,
    /** 错误 {code, message, retriable} */
    ERROR,
    /** 终态 {status, summary, usage} */
    DONE
}
