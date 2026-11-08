package com.markagent.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 原子 JSON 文件读写底座：先写 *.tmp 再原子 rename，进程内同步 + 跨进程文件锁。
 * 所有 JsonFile* 存储实现共用此底座。
 */
public final class AtomicJsonFile {

    private static final Logger log = LoggerFactory.getLogger(AtomicJsonFile.class);

    private final Path file;

    public AtomicJsonFile(Path file) {
        this.file = file;
    }

    public Path path() {
        return file;
    }

    public boolean exists() {
        return Files.exists(file);
    }

    /** 读全部文本（不存在返回 null） */
    public synchronized String readString() throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
             FileLock lock = ch.lock(0, Long.MAX_VALUE, true)) {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        }
    }

    /** 读为 JsonNode（不存在返回 null） */
    public synchronized JsonNode readJson(ObjectMapper mapper) throws IOException {
        String s = readString();
        return s == null ? null : mapper.readTree(s);
    }

    /** 读为对象（不存在返回 null） */
    public synchronized <T> T readAs(ObjectMapper mapper, Class<T> type) throws IOException {
        String s = readString();
        return s == null ? null : mapper.readValue(s, type);
    }

    /** 原子写入：tmp + fsync + ATOMIC_MOVE */
    public synchronized void writeString(String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicNotSupported) {
            // 部分文件系统不支持 ATOMIC_MOVE（如某些网络盘），退化为普通移动
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized void writeJson(ObjectMapper mapper, Object value) throws IOException {
        ObjectMapper copy = mapper.copy();
        copy.enable(SerializationFeature.INDENT_OUTPUT);
        writeString(copy.writeValueAsString(value));
    }

    /** 删除（不存在时静默） */
    public synchronized void delete() throws IOException {
        Files.deleteIfExists(file);
    }

    /** 追加一行（jsonl 事件流水用）。单行写入天然避免半行撕裂，跨进程锁交给操作系统 O_APPEND 语义 */
    public synchronized void appendLine(String line) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }
}
