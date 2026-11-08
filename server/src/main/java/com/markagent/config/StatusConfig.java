package com.markagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

/** 运行中任务计数（/status 展示） */
@Configuration
public class StatusConfig {

    @Bean
    public AtomicInteger runningTasks() {
        return new AtomicInteger(0);
    }
}
