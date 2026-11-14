package com.markagent.cron;

import com.markagent.api.GlobalExceptionHandler;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 定时任务 REST 端点（硬约束下与 cron 域同包，@RestController 组件扫描不受包名限制）。
 * userId 取自 JWT principal；归属校验同 ChatController.requireOwned 模式：非本人一律 404。
 */
@RestController
@RequestMapping("/api/v1/cron")
public class CronController {

    private final CronJobService service;

    public CronController(CronJobService service) {
        this.service = service;
    }

    /** 当前用户全部定时任务 */
    @GetMapping
    public List<CronJob> list(Authentication authentication) {
        return service.list(authentication.getName());
    }

    /** 创建定时任务：body {name, cronExpression, prompt}；cron 非法返回 400 带格式说明 */
    @PostMapping
    public CronJob create(@RequestBody CronCreateRequest request, Authentication authentication) {
        try {
            return service.create(authentication.getName(), request.name(),
                    request.cronExpression(), request.prompt());
        } catch (IllegalArgumentException e) {
            throw GlobalExceptionHandler.badRequest(e.getMessage());
        }
    }

    /** 启用（从当前时刻重算下次执行时间） */
    @PostMapping("/{id}/enable")
    public CronJob enable(@PathVariable("id") String id, Authentication authentication) {
        return setEnabled(id, authentication, true);
    }

    /** 停用（下次执行时间置空） */
    @PostMapping("/{id}/disable")
    public CronJob disable(@PathVariable("id") String id, Authentication authentication) {
        return setEnabled(id, authentication, false);
    }

    /** 删除：不存在或非本人一律 404（不泄露他人任务存在性） */
    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable("id") String id, Authentication authentication) {
        if (!service.delete(authentication.getName(), id)) {
            throw GlobalExceptionHandler.notFound("定时任务 " + id);
        }
        return Map.of("status", "deleted");
    }

    private CronJob setEnabled(String id, Authentication authentication, boolean enabled) {
        return service.setEnabled(authentication.getName(), id, enabled)
                .orElseThrow(() -> GlobalExceptionHandler.notFound("定时任务 " + id));
    }
}
