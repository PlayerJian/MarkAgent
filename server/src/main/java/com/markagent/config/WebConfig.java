package com.markagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源与 SPA 托管（M8）。
 * <ul>
 *   <li>classpath:/static/ 为 Boot 默认静态位置（web/dist 经 maven-resources-plugin
 *       在 process-resources 阶段拷入 target/classes/static，jar 内自带前端）</li>
 *   <li>markagent.web.static-locations 配非默认值（如 file:../web/dist/）时在此注册，
 *       支持前端独立部署 / 开发态直读产物</li>
 * </ul>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final MarkAgentProperties properties;

    public WebConfig(MarkAgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = properties.web().staticLocations();
        if (location != null && !location.isBlank() && !"classpath:/static/".equals(location)) {
            registry.addResourceHandler("/**")
                    .addResourceLocations(location)
                    .setCachePeriod(3600);
        }
    }
}
