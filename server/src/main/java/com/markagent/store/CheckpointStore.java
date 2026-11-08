package com.markagent.store;

import com.markagent.common.dto.CheckpointRecord;

import java.util.List;

/** 断点检查点持久化。stateKey = "userId/sessionId"，指向 AgentScope 持久化状态 */
public interface CheckpointStore {

    void save(String taskId, CheckpointRecord checkpoint);

    /** 按任务列出检查点（seq 升序） */
    List<CheckpointRecord> list(String taskId);

    void delete(String taskId);
}
