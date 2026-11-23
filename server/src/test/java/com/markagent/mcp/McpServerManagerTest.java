package com.markagent.mcp;

import com.markagent.store.McpConfigStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * McpServerManager 单测：connect/attach 路径依赖真实 MCP 进程与网络
 * （McpClientBuilder 为静态工厂，无法替换），整体不可测——此处只覆盖
 * 可离线验证的静态工具方法 truncateDescription 边界。
 */
class McpServerManagerTest {

    @Test
    void truncates2049CharsWithEllipsis() {
        String desc = "x".repeat(2049);
        String truncated = McpServerManager.truncateDescription(desc);
        // 2048 字符正文 + 1 字符省略号
        assertEquals(2049, truncated.length());
        assertTrue(truncated.startsWith("x".repeat(2048)));
        assertTrue(truncated.endsWith("…"));
    }

    @Test
    void keeps2048CharsUntouched() {
        String desc = "x".repeat(2048);
        assertEquals(desc, McpServerManager.truncateDescription(desc));
        assertTrue(McpServerManager.truncateDescription(desc).endsWith("x"));
    }

    @Test
    void keepsShortDescriptionsUntouched() {
        assertEquals("hello", McpServerManager.truncateDescription("hello"));
        assertEquals("", McpServerManager.truncateDescription(""));
        assertNull(McpServerManager.truncateDescription(null));
    }

    @Test
    void descriptionMaxConstantIs2048() {
        assertEquals(2048, McpServerManager.DESCRIPTION_MAX);
        // 构造器可用 Mockito 替身 store 直接实例化（connectConcurrency 显式传参）
        McpServerManager manager = new McpServerManager(mock(McpConfigStore.class), 3);
        assertFalse(manager.isConnected("nonexistent"));
    }
}
