package com.markagent.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** MCP server 连通性测试结果 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpTestResult(boolean ok, long latencyMs, List<String> tools, String error) {
}
