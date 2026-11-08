package com.markagent.store;

import com.markagent.common.dto.UserRecord;

import java.util.List;
import java.util.Optional;

/** 用户持久化。密码仅以 BCrypt 哈希形式出入本接口 */
public interface UserStore {

    /** 含密码哈希的内部记录 */
    record UserEntry(String id, String username, String displayName, String passwordHash, String createdAt) {
        public UserRecord toRecord() {
            return new UserRecord(id, username, displayName, createdAt);
        }
    }

    Optional<UserEntry> findByUsername(String username);

    Optional<UserEntry> findById(String id);

    List<UserEntry> listAll();

    void save(UserEntry user);
}
