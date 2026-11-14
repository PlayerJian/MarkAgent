package com.markagent.tools;

import java.net.URI;

/**
 * 网络工具（http_get/http_post/web_fetch）入口 URL 校验，参考 Claude Code WebFetch 的
 * {@code validateURL}：长度上限、可解析、无凭据、协议白名单。
 * <p>
 * 返回 null 表示通过；否则返回面向模型的中文错误消息（直接作为工具结果返回，
 * 便于模型据此修正参数重试）。
 */
public final class UrlChecks {

    /** URL 长度上限（对齐参考实现 MAX_URL_LENGTH：太长的签名 URL 多为数据外带通道） */
    public static final int MAX_URL_LENGTH = 2000;

    private UrlChecks() {
    }

    /**
     * 基础校验（http_get/http_post 用）：scheme 白名单 + 长度 + 可解析 + 无凭据 + 必须有主机。
     * 不要求公网域名——http_get 也可能访问部署环境内的 API。
     */
    public static String validateHttp(String rawUrl) {
        String message = validateCommon(rawUrl);
        if (message != null) {
            return message;
        }
        return null;
    }

    /**
     * 严格校验（web_fetch 用）：在基础校验之上要求 hostname 至少含一个点
     * （对齐参考实现「非特权、公网可达域名」过滤：拒绝 localhost/内网单标签主机）。
     */
    public static String validateFetch(String rawUrl) {
        String message = validateCommon(rawUrl);
        if (message != null) {
            return message;
        }
        String host = URI.create(rawUrl.trim()).getHost();
        if (host == null || !host.contains(".")) {
            return "错误：URL 主机名不是公网域名（拒绝 localhost/内网单标签主机）";
        }
        return null;
    }

    /** 两档共用的基础校验；返回错误消息或 null */
    private static String validateCommon(String rawUrl) {
        if (rawUrl == null) {
            return "错误：url 缺失";
        }
        String url = rawUrl.trim();
        if (url.isEmpty()) {
            return "错误：url 为空";
        }
        if (url.length() > MAX_URL_LENGTH) {
            return "错误：URL 过长（超过 " + MAX_URL_LENGTH + " 字符），请缩短或改用其他方式获取";
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return "错误：URL 无法解析 - " + e.getMessage();
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return "错误：仅支持 http/https 协议";
        }
        if (uri.getUserInfo() != null) {
            return "错误：URL 不允许携带用户名/密码（凭据请通过其他安全通道传递）";
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return "错误：URL 缺少主机名";
        }
        return null;
    }
}
