package com.agent.tool.model;

import java.util.Map;

/**
 * 工具实现契约。
 *
 * <p>实现方**不得**自行做白名单 / Schema 校验（那是 {@code ToolGuard} 的职责，R5-07），
 * 也不得自行写审计（{@code ToolAuditLog} 的职责，R5-08）——避免校验与审计出现第二份真相。
 */
public interface ToolHandler {

    /** 工具名（须与 {@link ToolMeta#name()} 一致，注册时校验） */
    String name();

    /** 执行；异常会被 {@code ToolExecutor} 捕获并转成友好错误（R5-02） */
    ToolOutcome execute(Map<String, Object> arguments, ToolContext context) throws Exception;
}