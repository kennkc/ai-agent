package com.agent.tool.tools;

import com.agent.tool.model.ToolContext;
import com.agent.tool.model.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 计算器工具（R5-04）：表达式正确性 + **无 eval 的注入面** */
class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool();
    private final ToolContext context = ToolContext.of("test", "call-test");

    private ToolOutcome eval(String expr) {
        return tool.execute(Map.of("expr", expr), context);
    }

    @Test
    void basicArithmetic() {
        assertEquals("2176", eval("128*17").output(), "组合任务 Demo 的固定用例：128×17=2176");
        assertTrue(eval("1+2*3").output().equals("7"), "乘法优先于加法");
        assertTrue(eval("(1+2)*3").output().equals("9"), "括号优先");
        assertTrue(eval("10/4").output().equals("2.5"));
        assertTrue(eval("10%3").output().equals("1"));
        assertTrue(eval("2^10").output().equals("1024"));
        assertTrue(eval("-3+5").output().equals("2"), "一元负号");
    }

    @Test
    void functionsSupported() {
        assertEquals("2", eval("sqrt(4)").output());
        assertEquals("1024", eval("pow(2,10)").output());
        assertEquals("3", eval("max(1,3,2)").output());
        assertEquals("1", eval("min(1,3,2)").output());
        assertEquals("5", eval("abs(-5)").output());
    }

    @Test
    void divisionByZeroIsFriendlyErrorNotCrash() {
        ToolOutcome outcome = eval("1/0");
        assertFalse(outcome.success());
        assertEquals("AGENT_TOOL_EXEC_FAILED", outcome.errorCode());
        assertTrue(outcome.errorMessage().contains("除以零"), "错误要能被人读懂（R5-02 验收：错误友好）");
    }

    @Test
    void unknownFunctionAndIllegalCharsRejected() {
        assertFalse(eval("__import__('os')").success(), "不允许任何标识符逃逸到宿主");
        assertFalse(eval("os.system('ls')").success());
        assertFalse(eval("1;$x").success());
        assertFalse(eval("nope(1)").success());
        assertFalse(eval("(1+2").success(), "括号不闭合必须报错");
    }

    @Test
    void missingArgumentRejected() {
        ToolOutcome outcome = tool.execute(Map.of(), context);
        assertFalse(outcome.success());
        assertEquals("AGENT_TOOL_ARGS_INVALID", outcome.errorCode());
    }

    @Test
    void schemaDeclaresRequiredExprAndForbidsExtra() {
        String schema = CalculatorTool.schema();
        assertTrue(schema.contains("\"required\""), "schema 必须声明 required");
        assertTrue(schema.contains("\"expr\""));
        assertTrue(schema.contains("additionalProperties"), "未声明字段应被拒绝（防参数走私）");
    }
}