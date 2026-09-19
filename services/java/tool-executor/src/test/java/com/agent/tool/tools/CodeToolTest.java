package com.agent.tool.tools;

import com.agent.tool.model.ToolContext;
import com.agent.tool.model.ToolOutcome;
import com.agent.tool.sandbox.SandboxExecutor;
import com.agent.tool.sandbox.SandboxResult;
import com.agent.tool.sandbox.SandboxSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 代码执行工具（R5-05）。
 *
 * <p>本文件只验 **CodeTool 的标准化职责**（真执行在后端）；沙箱闸本身由
 * {@code SandboxExecutorTest} 覆盖，真跑 stdout 由端到端脚本覆盖。
 */
class CodeToolTest {

    private final SandboxExecutor sandbox = mock(SandboxExecutor.class);
    private final CodeTool tool = new CodeTool(sandbox);
    private final ToolContext context = ToolContext.of("test", "call-code");

    @Test
    void stdoutIsReturnedAndSandboxBackendIsExposed() {
        when(sandbox.execute(any(SandboxSpec.class))).thenReturn(
                new SandboxResult(true, "2176\n", "", 0, "docker", false, false, null));

        ToolOutcome outcome = tool.execute(Map.of("code", "print(128*17)"), context);

        assertTrue(outcome.success());
        assertEquals("2176", outcome.output(), "stdout 应被 strip 后返回");
        assertEquals("docker", outcome.data().get("sandbox_backend"),
                "这次到底跑在哪必须对调用方可见（R-C05 执行视图要用）");
        assertEquals(false, outcome.data().get("sandbox_degraded"));
    }

    @Test
    void degradedBackendIsLabelledNotHidden() {
        when(sandbox.execute(any(SandboxSpec.class))).thenReturn(
                new SandboxResult(true, "hi", "", 0, "process-restricted", true, false,
                        "非隔离边界：仅受限子进程 + 静态预检，不等价于沙箱"));

        ToolOutcome outcome = tool.execute(Map.of("code", "print('hi')"), context);

        assertTrue(outcome.success());
        assertEquals(true, outcome.data().get("sandbox_degraded"), "降级必须如实标注，不得静默");
        assertEquals("process-restricted", outcome.data().get("sandbox_backend"));
    }

    @Test
    void rejectedCodeBecomesSandboxRejectedError() {
        when(sandbox.execute(any(SandboxSpec.class))).thenReturn(
                SandboxResult.rejected("代码命中危险操作规则，已拦截（2 条规则），未进入沙箱执行"));

        ToolOutcome outcome = tool.execute(Map.of("code", "import os\nos.system('rm -rf /')"), context);

        assertFalse(outcome.success());
        assertEquals("AGENT_SANDBOX_REJECTED", outcome.errorCode());
        assertEquals(true, outcome.data().get("rejected"));
    }

    @Test
    void nonZeroExitBecomesExecFailedWithStderr() {
        when(sandbox.execute(any(SandboxSpec.class))).thenReturn(
                new SandboxResult(false, "", "ZeroDivisionError: division by zero\n", 1, "docker", false, false, null));

        ToolOutcome outcome = tool.execute(Map.of("code", "print(1/0)"), context);

        assertFalse(outcome.success());
        assertEquals("AGENT_TOOL_EXEC_FAILED", outcome.errorCode());
        assertEquals("ZeroDivisionError: division by zero\n", outcome.data().get("stderr"));
        assertEquals(1, outcome.data().get("exit_code"));
    }

    @Test
    void missingCodeRejected() {
        assertEquals("AGENT_TOOL_ARGS_INVALID", tool.execute(Map.of(), context).errorCode());
    }

    @Test
    void schemaRequiresCodeAndRestrictsLanguage() {
        String schema = CodeTool.schema();
        assertTrue(schema.contains("\"required\""));
        assertTrue(schema.contains("\"code\""));
        assertTrue(schema.contains("\"python\""), "language 应限定为受支持集合");
    }
}