package com.markagent.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 路由回退：前端深链（如 /tasks/xxx）刷新/直达时由服务端转发到 index.html，
 * 路由交给 React Router 处理。静态文件（/assets/**、/sw.js 等）由资源处理器直出，不受影响。
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/login", "/tasks", "/tasks/{id}", "/memory", "/skills", "/mcp", "/docs", "/settings"})
    public String spa() {
        return "forward:/index.html";
    }
}
