package com.markagent.cron;

/**
 * 定时任务触发器抽象：默认实现调用 AgentChatService.start 受理任务；
 * 单测注入假触发器验证调度判定，无需构造 ReActAgent 等重量级依赖。
 */
@FunctionalInterface
public interface CronTrigger {

    /** 触发一次任务执行；实现方异常由调度循环兜底记录，不影响循环存活 */
    void fire(CronJob job);
}
