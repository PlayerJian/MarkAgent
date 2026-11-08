package com.markagent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

/**
 * CLI 本地配置：~/.markagent/config.json（server 地址 + JWT token + 用户名）。
 * login 后 token 落盘，logout 清除；服务地址优先级：-s 参数 &gt; 配置文件 &gt; 默认值。
 */
public final class CliConfig {

    public static final String DEFAULT_SERVER = "http://localhost:8080";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CliConfig() {
    }

    public static Path configFile() {
        return Path.of(System.getProperty("user.home"), ".markagent", "config.json");
    }

    /** 读取配置（文件不存在返回空对象） */
    public static JsonNode load() {
        Path file = configFile();
        if (!Files.exists(file)) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(file.toFile());
        } catch (IOException e) {
            throw new CliException("读取配置失败 " + file + ": " + e.getMessage());
        }
    }

    public static void save(JsonNode node) {
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } catch (IOException e) {
            throw new CliException("写入配置失败 " + file + ": " + e.getMessage());
        }
    }

    /** 变更配置并落盘 */
    public static void update(UnaryOperator<ObjectNode> fn) {
        ObjectNode node = (ObjectNode) load();
        fn.apply(node);
        save(node);
    }

    public static String token() {
        JsonNode node = load();
        return node.hasNonNull("token") ? node.get("token").asText() : null;
    }

    /** 生效服务地址：-s 参数 &gt; 配置文件 &gt; 默认值 */
    public static String server(String cliValue) {
        JsonNode node = load();
        if (cliValue != null && !cliValue.isBlank() && !cliValue.equals(DEFAULT_SERVER)) {
            return stripSlash(cliValue);
        }
        if (node.hasNonNull("server")) {
            return stripSlash(node.get("server").asText());
        }
        return DEFAULT_SERVER;
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** 要求已登录：返回 token，未登录抛错 */
    public static String requireToken() {
        String token = token();
        if (token == null || token.isBlank()) {
            throw new CliException("未登录：请先运行 markagent login（首次使用加 --register）");
        }
        return token;
    }
}
