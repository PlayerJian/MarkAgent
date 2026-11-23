package com.markagent.integration;

/**
 * IM 通知能力接口（可插拔企业集成层）。
 * <p>
 * 默认实现 {@link WebhookNotifier}：企微风格通用 webhook（POST JSON），
 * 运行时开关 markagent.integration.notify.enabled（默认 false）。
 * 接入钉钉（需自建桥接/加签）或其他 IM 时提供本接口的替代实现并替换 Bean 即可。
 * <p>
 * 返回约定：成功返回发送结果文本；失败返回以「错误：」开头的文本。
 * 实现不得抛出异常，错误一律以文本返回。
 */
public interface Notifier {

    /**
     * 发送一条通知。
     *
     * @param title   通知标题（一行摘要）
     * @param content 通知正文
     * @return 发送结果文本；失败以「错误：」开头
     */
    String send(String title, String content);
}
