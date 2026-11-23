package com.markagent.tools;

import com.markagent.mcp.McpServerManager;
import com.markagent.store.McpConfigStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpSyncClientWrapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * McpResourceTool 单测。
 * <p>
 * 关键策略：AgentScope 2.0.4 未暴露 resources API（javap 验证），工具经反射取
 * McpSyncClientWrapper 私有 client 字段直调底层 SDK。本测试用「真实 wrapper +
 * Mockito 假传输层（内存 JSON-RPC 往返）」全链路验证——若 AgentScope 未来改变
 * 字段名/结构，rawClientOnRealSyncWrapperReturnsSdkClient 会直接失败，钉住反射契约。
 * <p>
 * 覆盖：参数校验与连接检查（无真实 MCP 进程）、反射失败路径（mock wrapper 无
 * client 字段 → 明确错误）、真实 SDK client 的 list/read 往返、每服务器错误隔离、
 * 格式化纯函数（行格式/占位/截断/二进制说明/混合片段）。
 */
class McpResourceToolTest {

    // ==================== 测试替身 ====================

    /** 空连接的真实 manager（clients 为空，无需真实 MCP 进程） */
    private static McpServerManager emptyManager() {
        return new McpServerManager(mock(McpConfigStore.class), 3);
    }

    /** 已连接一台服务器（srv1，mock wrapper）的替身 manager：仅校验层可用，读取会走到反射失败路径 */
    private static McpServerManager connectedManager() {
        McpServerManager manager = mock(McpServerManager.class);
        when(manager.connectedClients()).thenReturn(Map.of("srv1", mock(McpClientWrapper.class)));
        return manager;
    }

    /**
     * 假传输层：内存 JSON-RPC 往返，模拟 SDK 0.17.2 真实传输的通道结构——
     * sendMessage 只回 Mono.empty()（发送确认，Mono&lt;Void&gt;），响应经 connect(handler)
     * 登记的入站处理器回送（真实传输中即 stdin/SSE 入站通道）。resources/list 返回两条
     * 资源，resources/read 返回一段文本；unmarshalFrom 恒等透传（结果已是类型化对象）。
     */
    private static McpClientTransport fakeTransport() {
        McpClientTransport transport = mock(McpClientTransport.class);
        AtomicReference<Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>> inbound
                = new AtomicReference<>();
        when(transport.protocolVersions()).thenReturn(List.of("2025-06-18"));
        when(transport.closeGracefully()).thenReturn(Mono.empty());
        when(transport.connect(any())).thenAnswer(inv -> {
            // 登记入站处理器：响应回送通道（真实传输在此挂接收管道）
            @SuppressWarnings("unchecked")
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler
                    = inv.getArgument(0);
            inbound.set(handler);
            return Mono.empty();
        });
        when(transport.sendMessage(any())).thenAnswer(inv -> {
            McpSchema.JSONRPCMessage message = (McpSchema.JSONRPCMessage) inv.getArgument(0);
            // 通知类（notifications/initialized 等）无响应，发送确认即完成
            if (message instanceof McpSchema.JSONRPCNotification) {
                return Mono.empty();
            }
            McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) message;
            Object result = switch (request.method()) {
                // 握手：声明 resources 能力，否则 SDK 客户端拒绝资源读取
                case "initialize" -> new McpSchema.InitializeResult("2025-06-18",
                        new McpSchema.ServerCapabilities(null, null, null, null,
                                new McpSchema.ServerCapabilities.ResourceCapabilities(false, false), null),
                        new McpSchema.Implementation("fake-server", "1.0.0"), null);
                case "resources/list" -> new McpSchema.ListResourcesResult(List.of(
                        McpSchema.Resource.builder().uri("file:///etc/config.txt").name("配置")
                                .mimeType("text/plain").build(),
                        McpSchema.Resource.builder().uri("file:///data/report.md").name("报告")
                                .mimeType("text/markdown").build()),
                        null);
                case "resources/read" -> new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("file:///etc/config.txt", "text/plain",
                                "port=8080\ndebug=false")));
                default -> new McpSchema.ListResourcesResult(List.of(), null);
            };
            McpSchema.JSONRPCResponse response = new McpSchema.JSONRPCResponse("2.0", request.id(), result, null);
            // 经入站通道回送响应（真实传输为 stdin/SSE 入站，这里直连登记的 handler）
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler = inbound.get();
            if (handler != null) {
                handler.apply(Mono.just(response)).subscribe();
            }
            return Mono.empty();
        });
        when(transport.unmarshalFrom(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        return transport;
    }

    /** 真实 McpSyncClientWrapper（真实私有 client 字段）+ 假传输层 */
    private static McpClientWrapper realSyncWrapper(String name, McpClientTransport transport) {
        return new McpSyncClientWrapper(name, McpClient.sync(transport).build());
    }

    // ==================== 参数校验 / 连接检查（与旧版一致的契约） ====================

    @Test
    void listNoConnectedServersGivesClearHint() {
        McpResourceTool tool = new McpResourceTool(emptyManager());
        assertTrue(tool.listResources(null).contains("没有已连接的 MCP 服务器"));
    }

    @Test
    void listUnknownServerIdReportsErrorWithConnectedList() {
        McpResourceTool tool = new McpResourceTool(connectedManager());
        String out = tool.listResources("unknown");
        assertTrue(out.contains("MCP 服务器未连接: unknown"), out);
        assertTrue(out.contains("srv1"), out); // 附已连接服务器清单
    }

    @Test
    void readMissingServerId() {
        McpResourceTool tool = new McpResourceTool(connectedManager());
        assertTrue(tool.readResource(null, "file:///x").contains("server_id 不能为空"));
        assertTrue(tool.readResource("  ", "file:///x").contains("server_id 不能为空"));
    }

    @Test
    void readMissingUri() {
        McpResourceTool tool = new McpResourceTool(connectedManager());
        assertTrue(tool.readResource("srv1", null).contains("uri 不能为空"));
        assertTrue(tool.readResource("srv1", "").contains("uri 不能为空"));
    }

    @Test
    void readUnconnectedServerReportsError() {
        McpResourceTool tool = new McpResourceTool(connectedManager());
        String out = tool.readResource("unknown", "file:///x");
        assertTrue(out.contains("MCP 服务器未连接: unknown"), out);
        // 无连接服务器场景（空 manager）：同样给出未连接错误
        assertTrue(new McpResourceTool(emptyManager()).readResource("srv1", "file:///x")
                .contains("MCP 服务器未连接: srv1"));
    }

    @Test
    void checkConnectedNullBlankServerIdReturnsNull() {
        Map<String, ?> clients = Map.of("srv1", "wrapper");
        assertNull(McpResourceTool.checkConnected(clients, null));
        assertNull(McpResourceTool.checkConnected(clients, "  "));
    }

    @Test
    void checkConnectedKnownIdReturnsNull() {
        Map<String, ?> clients = Map.of("srv1", "wrapper");
        assertNull(McpResourceTool.checkConnected(clients, "srv1"));
    }

    @Test
    void checkConnectedUnknownIdListsConnectedServers() {
        Map<String, ?> clients = Map.of("srv1", "wrapper", "srv2", "wrapper2");
        String out = McpResourceTool.checkConnected(clients, "nope");
        assertNotNull(out);
        assertTrue(out.contains("nope"), out);
        assertTrue(out.contains("srv1") && out.contains("srv2"), out);
    }

    // ==================== 反射取底层 client ====================

    @Test
    void rawClientOnMockWrapperWithoutFieldFailsClearly() {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> McpResourceTool.rawClient(wrapper));
        assertTrue(e.getMessage().contains("client"), e.getMessage());
    }

    /** 契约钉：AgentScope wrapper 的私有 client 字段名变化时此测试失败 */
    @Test
    void rawClientOnRealSyncWrapperReturnsSdkClient() {
        McpSyncClient sync = McpClient.sync(fakeTransport()).build();
        McpClientWrapper wrapper = new McpSyncClientWrapper("srv1", sync);
        assertSame(sync, McpResourceTool.rawClient(wrapper));
    }

    /** 握手契约：假传输层必须能支撑 SDK 的 initialize 握手（resources 能力声明） */
    @Test
    void syncClientInitializeAgainstFakeTransport() {
        McpSyncClient sync = McpClient.sync(fakeTransport()).build();
        assertNotNull(sync.initialize());
        assertTrue(sync.isInitialized());
    }

    @Test
    void listWithUnreachableClientReportsPerServerError() {
        // mock wrapper 无 client 字段 → 反射失败 → 错误段而非整工具崩溃
        McpResourceTool tool = new McpResourceTool(connectedManager());
        String out = tool.listResources("srv1");
        assertTrue(out.contains("【srv1】"), out);
        assertTrue(out.contains("错误：列出资源失败"), out);
        String read = tool.readResource("srv1", "file:///x");
        assertTrue(read.contains("错误：读取 MCP 资源失败 [srv1] file:///x"), read);
    }

    // ==================== 真实 SDK client + 假传输层全链路 ====================

    @Test
    void listResourcesRoundTripThroughRealSdkClient() {
        McpServerManager manager = mock(McpServerManager.class);
        McpClientWrapper wrapper = realSyncWrapper("srv1", fakeTransport()); // 先建 wrapper 再 stub：避免 when() 内嵌套 stubbing
        when(manager.connectedClients()).thenReturn(Map.of("srv1", wrapper));
        McpResourceTool tool = new McpResourceTool(manager);
        String out = tool.listResources("srv1");
        assertTrue(out.contains("共列出 2 条资源"), out);
        assertTrue(out.contains("【srv1】"), out);
        assertTrue(out.contains("- file:///etc/config.txt | 配置 | text/plain"), out);
        assertTrue(out.contains("- file:///data/report.md | 报告 | text/markdown"), out);
    }

    @Test
    void listResourcesIsolatesPerServerErrors() {
        McpServerManager manager = mock(McpServerManager.class);
        McpClientWrapper good = realSyncWrapper("good", fakeTransport());
        when(manager.connectedClients()).thenReturn(Map.of(
                "good", good,
                "bad", mock(McpClientWrapper.class)));
        McpResourceTool tool = new McpResourceTool(manager);
        String out = tool.listResources(null);
        assertTrue(out.contains("file:///etc/config.txt"), out); // good 正常列出
        assertTrue(out.contains("【bad】"), out);
        assertTrue(out.contains("错误：列出资源失败"), out);
    }

    @Test
    void readResourceRoundTripThroughRealSdkClient() {
        McpServerManager manager = mock(McpServerManager.class);
        McpClientWrapper wrapper = realSyncWrapper("srv1", fakeTransport()); // 先建 wrapper 再 stub：避免 when() 内嵌套 stubbing
        when(manager.connectedClients()).thenReturn(Map.of("srv1", wrapper));
        McpResourceTool tool = new McpResourceTool(manager);
        String out = tool.readResource("srv1", "file:///etc/config.txt");
        assertTrue(out.contains("[srv1] file:///etc/config.txt（text/plain）"), out);
        assertTrue(out.contains("port=8080"), out);
        assertTrue(out.contains("debug=false"), out);
    }

    // ==================== 格式化纯函数 ====================

    @Test
    void formatResourceLineRendersUriNameMime() {
        McpSchema.Resource r = McpSchema.Resource.builder()
                .uri("file:///a.txt").name("名称").mimeType("text/plain").build();
        assertEquals("- file:///a.txt | 名称 | text/plain", McpResourceTool.formatResourceLine(r));
    }

    @Test
    void formatResourceLineBlanksToDash() {
        // SDK builder 会校验必填字段，空值场景用记录构造器（无校验）
        McpSchema.Resource r = new McpSchema.Resource("file:///b", null, null, null, null, null, null);
        assertEquals("- file:///b | - | -", McpResourceTool.formatResourceLine(r));
    }

    @Test
    void formatReadResultJoinsTextSegments() {
        McpSchema.ReadResourceResult result = new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents("file:///a", "text/plain", "第一段"),
                new McpSchema.TextResourceContents("file:///a", "text/plain", "第二段")));
        String out = McpResourceTool.formatReadResult("srv1", "file:///a", result);
        assertTrue(out.startsWith("[srv1] file:///a（text/plain）\n"), out);
        assertTrue(out.contains("第一段\n\n第二段"), out);
    }

    @Test
    void formatReadResultTruncatesAtLimit() {
        String longText = "x".repeat(McpResourceTool.TEXT_MAX + 100);
        McpSchema.ReadResourceResult result = new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents("file:///big", "text/plain", longText)));
        String out = McpResourceTool.formatReadResult("srv1", "file:///big", result);
        assertTrue(out.contains("内容已截断至 " + McpResourceTool.TEXT_MAX + " 字符"), out);
        assertFalse(out.contains(longText), out);
        assertTrue(out.endsWith("字符）"), out);
    }

    @Test
    void formatReadResultBinaryOnlyExplains() {
        McpSchema.ReadResourceResult result = new McpSchema.ReadResourceResult(List.of(
                new McpSchema.BlobResourceContents("file:///x.pdf", "application/pdf", "aGVsbG8=")));
        String out = McpResourceTool.formatReadResult("srv1", "file:///x.pdf", result);
        assertTrue(out.contains("二进制资源"), out);
        assertTrue(out.contains("application/pdf"), out);
        assertTrue(out.contains("改用该 MCP 服务器提供的工具"), out);
    }

    @Test
    void formatReadResultMixedTextAndBlobNotesBlobs() {
        McpSchema.ReadResourceResult result = new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents("file:///m", "text/plain", "文本"),
                new McpSchema.BlobResourceContents("file:///m", "application/pdf", "aGVsbG8=")));
        String out = McpResourceTool.formatReadResult("srv1", "file:///m", result);
        assertTrue(out.contains("文本"), out);
        assertTrue(out.contains("另有 1 个二进制片段未显示"), out);
    }

    @Test
    void formatReadResultEmptyContents() {
        McpSchema.ReadResourceResult result = new McpSchema.ReadResourceResult(List.of());
        String out = McpResourceTool.formatReadResult("srv1", "file:///empty", result);
        assertTrue(out.contains("资源内容为空"), out);
    }

    // ==================== connectedClients 只读视图 ====================

    @Test
    void connectedClientsIsReadOnlyView() {
        McpServerManager manager = emptyManager();
        Map<String, McpClientWrapper> view = manager.connectedClients();
        assertTrue(view.isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> view.put("x", mock(McpClientWrapper.class)));
    }

    // ==================== @Tool 注解契约 ====================

    @Test
    void toolAnnotationsContract() throws NoSuchMethodException {
        Method list = McpResourceTool.class.getMethod("listResources", String.class);
        Tool listAnn = list.getAnnotation(Tool.class);
        assertEquals("mcp_resources_list", listAnn.name());
        assertTrue(listAnn.strict());
        assertTrue(listAnn.readOnly());
        assertTrue(listAnn.concurrencySafe());
        assertFalse(listAnn.description().isBlank());
        assertFalse(listAnn.description().contains("不支持")); // 占位文案已移除

        Method read = McpResourceTool.class.getMethod("readResource", String.class, String.class);
        Tool readAnn = read.getAnnotation(Tool.class);
        assertEquals("mcp_resource_read", readAnn.name());
        assertTrue(readAnn.strict());
        assertTrue(readAnn.readOnly());
        assertTrue(readAnn.concurrencySafe());
        assertFalse(readAnn.description().isBlank());
        assertFalse(readAnn.description().contains("不支持"));
    }

    @Test
    void limitsAreAsSpecified() {
        assertEquals(20000, McpResourceTool.TEXT_MAX);
        assertEquals(200, McpResourceTool.LIST_MAX);
    }
}
