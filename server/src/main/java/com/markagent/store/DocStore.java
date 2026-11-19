package com.markagent.store;

import com.markagent.common.dto.DocRecord;
import com.markagent.common.dto.DocStatus;

import java.util.List;
import java.util.Optional;

/** 文档存储接口（JSON 文件实现，可换 JDBC） */
public interface DocStore {

    void save(DocRecord doc);

    Optional<DocRecord> get(String id);

    /** 按文档库列出 */
    List<DocRecord> list(String libraryId);

    /** 全量扫描（含所有用户），按状态过滤（服务启动恢复用） */
    List<DocRecord> listAll(DocStatus status);

    void delete(String id);

    /** 删除某文档库下的全部记录（删库级联用） */
    void deleteByLibrary(String libraryId);
}
