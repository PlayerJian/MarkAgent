package com.markagent.tools;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自研工具注册器：把 @Tool/@ToolParam 注解的工具对象转为 AgentTool 直接注册。
 * <p>
 * 不走 {@link Toolkit#registerTool(Object)}：其反射 schema 生成依赖 jsonschema-generator 4.38，
 * 与 Spring AI 2.0.2 引入的 5.0.0 在同一 classpath 冲突（NoSuchMethodError）。本注册器
 * 手写 JSON schema（参数类型取自方法签名），彻底绕开该依赖。
 */
public final class MarkAgentToolRegistrar {

    private MarkAgentToolRegistrar() {
    }

    /** 注册工具对象上所有 @Tool 注解方法 */
    public static void register(Toolkit toolkit, Object toolObject) {
        for (Method method : toolObject.getClass().getDeclaredMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool == null) {
                continue;
            }
            toolkit.registerAgentTool(toAgentTool(toolObject, method, tool));
        }
    }

    private static AgentTool toAgentTool(Object target, Method method, Tool tool) {
        Map<String, Object> schema = buildSchema(method);
        return new AgentTool() {
            @Override
            public String getName() {
                return tool.name();
            }

            @Override
            public String getDescription() {
                return tool.description();
            }

            @Override
            public Map<String, Object> getParameters() {
                return schema;
            }

            @Override
            public Boolean getStrict() {
                return tool.strict();
            }

            @Override
            public boolean isReadOnly() {
                return tool.readOnly();
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.fromCallable(() -> invoke(target, method, tool, param));
            }
        };
    }

    /** 工具调用捕获监听（audit 模块注册；null 时不做任何捕获）。任何监听异常绝不影响工具执行。 */
    @FunctionalInterface
    public interface ToolCallCaptureListener {
        void onToolCall(String taskId, String userId, String toolName, String callId,
                Map<String, Object> args, String resultText, long durationMs);
    }

    private static volatile ToolCallCaptureListener captureListener;

    /** 注册工具调用捕获监听（audit 模块装配时调用；重复注册覆盖旧监听） */
    public static void setToolCallCaptureListener(ToolCallCaptureListener listener) {
        captureListener = listener;
    }

    private static ToolResultBlock invoke(Object target, Method method, Tool tool, ToolCallParam param) {
        // 工具在 ToolExecutor 的独立线程执行，ThreadLocal 传不到；taskId/userId 经 RuntimeContext 传递后注入
        if (param.getRuntimeContext() != null) {
            if (param.getRuntimeContext().get("taskId") instanceof String taskId) {
                com.markagent.agent.model.TaskIdContext.set(taskId);
            }
            if (param.getRuntimeContext().getUserId() != null) {
                com.markagent.agent.model.TaskUserContext.set(param.getRuntimeContext().getUserId());
            }
        }
        long startedNanos = System.nanoTime();
        String text = "";
        try {
            method.setAccessible(true);
            Object[] args = convertArgs(method, param.getInput());
            Object result = method.invoke(target, args);
            text = result == null ? "" : String.valueOf(result);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            text = "错误：" + cause.getMessage();
        } catch (Exception e) {
            text = "错误：" + e.getMessage();
        } finally {
            // 审计钩子：唯一能同时拿到「入参 + 结果 + 耗时」的位置；须在 ThreadLocal 清理前捕获 taskId/userId
            ToolCallCaptureListener listener = captureListener;
            if (listener != null) {
                try {
                    listener.onToolCall(
                            com.markagent.agent.model.TaskIdContext.get(),
                            com.markagent.agent.model.TaskUserContext.get(),
                            tool.name(),
                            param.getToolUseBlock() == null ? tool.name() : param.getToolUseBlock().getId(),
                            param.getInput(),
                            text,
                            (System.nanoTime() - startedNanos) / 1_000_000);
                } catch (Exception ignored) {
                    // 审计失败绝不影响工具结果
                }
            }
            com.markagent.agent.model.TaskIdContext.clear();
            com.markagent.agent.model.TaskUserContext.clear();
        }
        String callId = param.getToolUseBlock() == null ? tool.name() : param.getToolUseBlock().getId();
        return new ToolResultBlock(callId, tool.name(), TextBlock.builder().text(text).build());
    }

    /** 注解参数 → JSON schema：{type:object, properties:{...}, required:[...]} */
    private static Map<String, Object> buildSchema(Method method) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            ToolParam toolParam = parameter.getAnnotation(ToolParam.class);
            if (toolParam == null) {
                continue;
            }
            properties.put(toolParam.name(), buildProp(parameter));
            if (toolParam.required()) {
                required.add(toolParam.name());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static String jsonType(Class<?> type) {
        if (type == int.class || type == long.class || type == double.class || type == float.class
                || Number.class.isAssignableFrom(type)) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        return "string";
    }

    /** 数组参数补 items 声明（如 plan_write 的 steps → object、web_search 的域名列表 → string） */
    private static Map<String, Object> buildProp(Parameter parameter) {
        ToolParam toolParam = parameter.getAnnotation(ToolParam.class);
        Map<String, Object> prop = new LinkedHashMap<>();
        if (List.class.isAssignableFrom(parameter.getType())) {
            prop.put("type", "array");
            prop.put("items", Map.of("type", listItemType(parameter)));
        } else if (Map.class.isAssignableFrom(parameter.getType())) {
            prop.put("type", "object");
        } else {
            prop.put("type", jsonType(parameter.getType()));
        }
        if (!toolParam.description().isBlank()) {
            prop.put("description", toolParam.description());
        }
        return prop;
    }

    /**
     * List 参数的元素 schema 类型：泛型擦除拿不到元素类，从 ParameterizedType 读实际类型参数——
     * List&lt;String&gt; → string，List&lt;Integer&gt; 等 → number，其余（含无法解析的泛型）→ object。
     */
    private static String listItemType(Parameter parameter) {
        java.lang.reflect.Type generic = parameter.getParameterizedType();
        if (generic instanceof java.lang.reflect.ParameterizedType parameterized) {
            java.lang.reflect.Type[] args = parameterized.getActualTypeArguments();
            if (args.length == 1 && args[0] == String.class) {
                return "string";
            }
            if (args.length == 1 && args[0] instanceof Class<?> clazz
                    && Number.class.isAssignableFrom(clazz)) {
                return "number";
            }
        }
        return "object";
    }

    /** 按参数名从工具输入取值并做基本类型转换；无 @ToolParam 的参数置 null */
    private static Object[] convertArgs(Method method, Map<String, Object> input) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            ToolParam toolParam = parameters[i].getAnnotation(ToolParam.class);
            if (toolParam == null) {
                continue;
            }
            Object value = input == null ? null : input.get(toolParam.name());
            args[i] = convert(value, parameters[i].getType());
        }
        return args;
    }

    private static Object convert(Object value, Class<?> type) {
        if (value == null) {
            return null;
        }
        // 集合/映射参数（如 plan_write 的 steps）直接透传
        if (List.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
            return value;
        }
        if (type == String.class) {
            return String.valueOf(value);
        }
        if (type == int.class || type == Integer.class) {
            return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
        }
        if (type == long.class || type == Long.class) {
            return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
        }
        if (type == double.class || type == Double.class) {
            return value instanceof Number number ? number.doubleValue() : Double.parseDouble(value.toString());
        }
        if (type == boolean.class || type == Boolean.class) {
            return value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
        }
        return value;
    }
}
