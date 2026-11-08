package com.markagent.store;

import com.markagent.common.dto.TaskRecord;
import com.markagent.common.dto.TaskStatus;

import java.util.List;
import java.util.Optional;

/** 任务持久化接口。当前实现 JsonFileTaskStore；接口抽象预留 JDBC 切换（M8 加固可选） */
public interface TaskStore {

    void save(TaskRecord task);

    Optional<TaskRecord> get(String taskId);

    /** 按用户列出任务（status 为 null 时不过滤） */
    List<TaskRecord> list(String userId, TaskStatus status);

    void delete(String taskId);
}
