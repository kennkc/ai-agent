package com.agent.tool.registry;

import com.agent.tool.model.ToolMeta;
import com.agent.tool.tools.CalculatorTool;
import com.agent.tool.tools.CodeTool;
import com.agent.tool.tools.HttpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置工具自注册 + 消费方登记（R5-01 / IN-06）。
 *
 * <p>三个内置工具（R5-04 计算/HTTP + R5-05 代码）在启动时注册进 {@link ToolRegistry}，
 * 并登记**声明过的消费方**，使 IN-06 的变更影响分析有确定数据源：
 * <ul>
 *   <li>{@code brain-planner}（Agent）：大脑规划器按意图选择工具；</li>
 *   <li>{@code combo-task}（流程）：组合任务"计算 + 查询"；</li>
 *   <li>{@code code-assist}（流程）：代码执行辅助。</li>
 * </ul>
 * 若日后这些消费方改用别的工具，**须同步改这里**，否则影响分析会漏报——
 * 这是本机制的已知边界（DEBT-019）。
 */
@Component
public class ToolBootstrap {
    private static final Logger log = LoggerFactory.getLogger(ToolBootstrap.class);

    private final ToolRegistry registry;
    private final CalculatorTool calculatorTool;
    private final HttpTool httpTool;
    private final CodeTool codeTool;

    public ToolBootstrap(ToolRegistry registry, CalculatorTool calculatorTool,
                         HttpTool httpTool, CodeTool codeTool) {
        this.registry = registry;
        this.calculatorTool = calculatorTool;
        this.httpTool = httpTool;
        this.codeTool = codeTool;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerBuiltins() {
        registry.register(new ToolMeta("calculator", "1.0.0",
                "安全的算术计算器：支持 + - * / % ^、括号与 sqrt/abs/min/max/round/pow/log 等函数。",
                CalculatorTool.schema(), false, 3000, List.of(), List.of(), false, null, null, "lifeform-core"),
                calculatorTool);

        registry.register(new ToolMeta("http", "1.0.0",
                "HTTP 请求工具（域名白名单 + 内网地址拦截）。用于查询公开 API，如天气。",
                HttpTool.schema(), false, 10000, httpTool.allowDomains(), List.of(),
                false, null, null, "lifeform-core"),
                httpTool);

        registry.register(new ToolMeta("code", "1.0.0",
                "在沙箱中执行 Python 代码片段，返回 stdout/stderr。危险操作在入沙箱前被拦截。",
                CodeTool.schema(), true, 12000, List.of(), List.of(), false, null, null, "lifeform-core"),
                codeTool);

        // IN-06 消费方登记（影响分析的数据源）
        registry.registerConsumer("calculator", "brain-planner", "combo-task");
        registry.registerConsumer("http", "brain-planner", "combo-task");
        registry.registerConsumer("code", "code-assist", "code-assist");

        log.info("内置工具已注册：{} 个（白名单 {}）",
                registry.list().size(), registry.list().stream().map(item -> item.meta().name()).toList());
    }
}