package com.markagent.agent.team;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import com.markagent.tools.MarkAgentToolRegistrar;
import org.springframework.stereotype.Component;

/**
 * Worker agent 工厂：为 spawn_agent 工具创建一次性专家 agent。
 * <ul>
 *   <li>无 stateStore —— 不落 AgentState，避免与 leader 的 (userId, sessionId) 状态互踩；
 *       任务级断点恢复只挂 leader，worker 结果随 leader 上下文持久化</li>
 *   <li>工具集裁剪：calculator/dateTime/httpRequest/web_search/web_fetch/workspaceFile，不含 spawn_agent
 *       （防无限派生）、planTool（计划只属于 leader）、skillTool/memoryTool</li>
 * </ul>
 */
@Component
public class WorkerAgentFactory {

    /** worker 单次任务最大迭代（leader 为 30） */
    private static final int WORKER_MAX_ITERS = 15;

    private final Model model;

    public WorkerAgentFactory(Model agentScopeModel) {
        this.model = agentScopeModel;
    }

    /** 创建 worker：sys-prompt = 角色说明，工具集为只读/计算/文件子集 */
    public ReActAgent create(String agentId, String role, com.markagent.tools.CalculatorTool calculator,
            com.markagent.tools.DateTimeTool dateTime,
            com.markagent.tools.HttpRequestTool httpRequest,
            com.markagent.tools.VisionTool visionTool,
            com.markagent.tools.WebSearchTool webSearch,
            com.markagent.tools.WebFetchTool webFetch,
            com.markagent.tools.WorkspaceFileTool workspaceFile,
            com.markagent.tools.FileSearchTool fileSearch,
            com.markagent.tools.FileEditTool fileEdit) {
        Toolkit toolkit = new Toolkit();
        MarkAgentToolRegistrar.register(toolkit, calculator);
        MarkAgentToolRegistrar.register(toolkit, dateTime);
        MarkAgentToolRegistrar.register(toolkit, httpRequest);
        MarkAgentToolRegistrar.register(toolkit, visionTool);
        MarkAgentToolRegistrar.register(toolkit, webSearch);
        MarkAgentToolRegistrar.register(toolkit, webFetch);
        MarkAgentToolRegistrar.register(toolkit, workspaceFile);
        MarkAgentToolRegistrar.register(toolkit, fileSearch);
        MarkAgentToolRegistrar.register(toolkit, fileEdit);

        String sysPrompt = """
                你是专家 worker「%s」，在一个多智能体团队中执行子任务。
                职责：%s
                规则：
                - 只做交给你的子任务，最终用中文输出简洁完整的结果汇报。
                - 需要外部数据用 http_get/http_post，需要最新资讯/实时信息检索用 web_search，需要抓取并提炼网页内容用 web_fetch，需要计算用 calculator，需要保存中间产物用 file_write。
                - 需要理解图片内容（识别文字/截图/图表）用 vision_analyze：image 传图片，task 写明具体视觉任务，context 写清团队整体目标与已完成的进展。
                - 工作区内搜索内容用 file_grep，按文件名找文件用 file_glob，修改文件用 file_edit。
                - 不调用 spawn_agent，不制定总计划，那是 leader 的职责。
                """.formatted(agentId, role);

        return ReActAgent.builder()
                .name(agentId)
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .maxIters(WORKER_MAX_ITERS)
                .build();
    }
}
