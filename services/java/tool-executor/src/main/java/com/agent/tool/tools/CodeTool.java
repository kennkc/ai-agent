package com.agent.tool.tools;

import com.agent.tool.model.ToolContext;
import com.agent.tool.model.ToolHandler;
import com.agent.tool.model.ToolOutcome;
import com.agent.tool.sandbox.SandboxExecutor;
import com.agent.tool.sandbox.SandboxResult;
import com.agent.tool.sandbox.SandboxSpec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 代码执行工具（R5-05）——**一律经 {@link SandboxExecutor}**，工具自身不做任何执行。
 *
 * <p>验收：代码运行返回 stdout/stderr；危险操作拦截。拦截发生在沙箱入口的静态预检，
 * 本工具只负责把沙箱结果标准化（含 {@code sandbox_backend} / {@code degraded} 透传，
 * 让"这次到底跑在哪"对调用方可观测）。
 */
@Component
public class CodeTool implements ToolHandler {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "code":       { "type": "string", "minLength": 1, "maxLength": 16384 },
                "language":   { "type": "string", "enum": ["python"] },
                "timeout_ms": { "type": "integer", "minimum": 100, "maximum": 15000 }
              },
              "required": ["code"],
              "additionalProperties": false
            }""";

    private final SandboxExecutor sandbox;

    public CodeTool(SandboxExecutor sandbox) { this.sandbox = sandbox; }

    public static String schema() { return SCHEMA; }

    @Override public String name() { return "code"; }

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, ToolContext context) {
        Object rawCode = arguments == null ? null : arguments.get("code");
        if (rawCode == null) return ToolOutcome.fail("AGENT_TOOL_ARGS_INVALID", "缺少参数 code");
        int timeoutMs = arguments.get("timeout_ms") == null ? 0
                : ((Number) arguments.get("timeout_ms")).intValue();

        SandboxResult result = sandbox.execute(SandboxSpec.python(String.valueOf(rawCode), timeoutMs, 0));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stdout", result.stdout());
        data.put("stderr", result.stderr());
        data.put("exit_code", result.exitCode());
        data.put("sandbox_backend", result.backend());
        data.put("sandbox_degraded", result.degraded());
        data.put("rejected", result.rejected());

        if (result.rejected()) {
            return ToolOutcome.fail("AGENT_SANDBOX_REJECTED", result.note(), data);
        }
        if (!result.success()) {
            return ToolOutcome.fail("AGENT_TOOL_EXEC_FAILED",
                    result.note() != null ? result.note() : "沙箱执行返回非零退出码 " + result.exitCode(), data);
        }
        String output = result.stdout() == null ? "" : result.stdout().strip();
        return ToolOutcome.ok(output.isEmpty() ? "（无输出）" : output, data);
    }
}