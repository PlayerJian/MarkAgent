package com.markagent.store;

import com.markagent.common.dto.DocLibraryRecord;

import java.util.List;
import java.util.Optional;

/** 文档库存储接口（JSON 文件实现，可换 JDBC） */
public interface DocLibraryStore {

    void save(DocLibraryRecord library);

    Optional<DocLibraryRecord> get(String id);

    /** 按属主列出（userId 非空过滤） */
    List<DocLibraryRecord> list(String userId);

    void delete(String id);
}
