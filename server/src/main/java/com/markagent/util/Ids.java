package com.markagent.util;

import java.security.SecureRandom;

/** 短 ID 生成器：t_/s_/u_/cp_/msg_ 前缀 + 时间戳 + 随机后缀 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

    private Ids() {
    }

    public static String next(String prefix) {
        long ts = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(prefix).append(ts & 0xFFFFFFF);
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static String task() {
        return next("t_");
    }

    public static String session() {
        return next("s_");
    }

    public static String user() {
        return next("u_");
    }

    public static String checkpoint() {
        return next("cp_");
    }

    public static String message() {
        return next("msg_");
    }

    public static String agent() {
        return next("a_");
    }
}
