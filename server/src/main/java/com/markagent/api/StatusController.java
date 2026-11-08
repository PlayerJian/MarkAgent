package com.markagent.api;

import com.markagent.common.dto.StatusInfo;
import com.markagent.config.MarkAgentProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** 健康状态（免认证） */
@RestController
@RequestMapping("/api/v1/status")
public class StatusController {

    private final MarkAgentProperties props;
    private final AtomicInteger runningTasks;

    public StatusController(MarkAgentProperties props, AtomicInteger runningTasks) {
        this.props = props;
        this.runningTasks = runningTasks;
    }

    @GetMapping
    public StatusInfo status() {
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        return new StatusInfo(
                "0.1.0",
                props.llm().provider(),
                props.llm().defaultModel(),
                runningTasks.get(),
                props.storage().root(),
                formatUptime(uptimeMs));
    }

    private static String formatUptime(long ms) {
        long s = ms / 1000;
        return String.format("%dh %02dm %02ds", s / 3600, (s % 3600) / 60, s % 60);
    }
}
