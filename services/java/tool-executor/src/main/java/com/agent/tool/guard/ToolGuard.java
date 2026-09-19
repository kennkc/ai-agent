package com.agent.tool.guard;

import com.agent.tool.common.BizException;
import com.agent.tool.common.ErrorCode;
import com.agent.tool.model.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具白名单 + 参数 JSON Schema 校验 + 敏感参数拦截（R5-07）。
 *
 * <p><b>三道闸，顺序不可换</b>：
 * <ol>
 *   <li><b>白名单</b>（{@code TOOL_WHITELIST}）——不在白名单的工具**直接拒绝，不触达执行器**；
 *       这是 R5-07 验收「白名单外拒绝」的落点。</li>
 *   <li><b>参数 Schema</b>——按工具 {@code parametersSchema}（JSON Schema Draft-07）逐字段校验，
 *       失败返回 **字段级 details**，便于 LLM 自我修正重试。</li>
 *   <li><b>敏感参数</b>——全局危险模式 + 工具级模式双重扫描**字符串参数**，
 *       命中即拦（文件删除 / 提权 / 反弹 shell / 路径穿越 / SSRF 内网地址）。</li>
 * </ol>
 *
 * <p><b>选型留痕</b>：开发设计文档写 "JSON Schema (everit)"，实装为 networknt
 * json-schema-validator —— 同为 Draft-07 校验器，能力等价，差异登记于《技术债台账》DEBT-020。
 */
@Component
public class ToolGuard {
    private static final Logger log = LoggerFactory.getLogger(ToolGuard.class);

    private final Set<String> whitelist = new LinkedHashSet<>();
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, JsonSchema> schemaCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 全局敏感参数模式（对应 R5-03 攻击样本集：文件删除 / 提权 / 网络外联 / 反弹 shell / 路径穿越） */
    private static final List<Pattern> GLOBAL_BLOCKED = List.of(
            Pattern.compile("(?i)\\brm\\s+(-[a-zA-Z]*\\s+)*-(rf|fr)|\\brm\\s+-r\\b|\\brm\\s+-f\\b"),
            Pattern.compile("(?i)(\\bmkfs(\\.[a-z0-9]+)?\\b|\\bfdisk\\b|\\bdd\\s+if=|:\\s*\\(\\s*\\)\\s*\\{)"),
            Pattern.compile("(?i)\\b(shutdown|reboot|halt|poweroff|init\\s+0)\\b"),
            Pattern.compile("(?i)\\b(sudo|su\\s+-|chmod\\s+777|chown\\s+root|setcap|usermod|visudo)\\b"),
            Pattern.compile("(?i)(/etc/shadow|/etc/passwd|/root/\\.ssh|id_rsa|\\.aws/credentials)"),
            Pattern.compile("(?i)\\b(bash\\s+-i\\s+>&|nc\\s+-e|ncat\\s+-e|/dev/tcp/|socat\\s+.*exec)"),
            Pattern.compile("(?i)(\\.\\./){2,}|%2e%2e%2f"),
            Pattern.compile("(?i)\\b(curl|wget)\\s+[^|]*\\|\\s*(sh|bash|zsh)\\b"),
            Pattern.compile("(?i)\\b(os\\.system|subprocess\\.(popen|call|run)|Runtime\\.getRuntime\\(\\)\\.exec)\\b"),
            // 文件系统破坏（不经 shell 也能删库：shutil.rmtree / os.remove / os.unlink / Path.unlink）
            Pattern.compile("(?i)(\\bshutil\\.rmtree\\b|\\bos\\.(remove|unlink)\\b|\\.unlink\\(\\s*\\))"),
            Pattern.compile("(?i)\\b(import\\s+socket|socket\\.socket|urllib\\.request|requests\\.(get|post))\\b")
    );

    /** SSRF 内网地址（HTTP 工具域名/IP 参数用） */
    private static final List<Pattern> INTERNAL_ADDRESS = List.of(
            Pattern.compile("(?i)^(https?://)?(localhost|127\\.|0\\.0\\.0\\.0|\\[::1\\])"),
            Pattern.compile("(?i)^(https?://)?169\\.254\\."),
            Pattern.compile("(?i)^(https?://)?10\\."),
            Pattern.compile("(?i)^(https?://)?192\\.168\\."),
            Pattern.compile("(?i)^(https?://)?172\\.(1[6-9]|2[0-9]|3[01])\\."),
            Pattern.compile("(?i)^(https?://)?(metadata|metadata\\.google\\.internal|169\\.254\\.169\\.254)")
    );

    public ToolGuard(@Value("${app.tool.whitelist:calculator,http,code}") String whitelistConfig) {
        Arrays.stream(whitelistConfig.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .forEach(whitelist::add);
        log.info("工具白名单已加载：{}", whitelist);
    }

    public Set<String> whitelist() { return Set.copyOf(whitelist); }

    /**
     * 闸 1：白名单。**不在白名单 → 拒绝且不触达执行器**（R5-07 验收）。
     * 注意：白名单是"允许集合"，**未注册的工具同样在此被拒**（先查注册表再查白名单由调用方保证）。
     */
    public void checkWhitelist(String toolName) {
        if (!whitelist.contains(toolName)) {
            throw new BizException(ErrorCode.AGENT_TOOL_NOT_ALLOWED,
                    "工具不在白名单：" + toolName, Map.of("tool_name", toolName,
                    "whitelist", String.join(",", whitelist)));
        }
    }

    /** 闸 2：JSON Schema 校验（失败带字段级 details） */
    public void checkSchema(ToolDefinition definition, Map<String, Object> arguments) {
        String schemaText = definition.meta().parametersSchema();
        if (schemaText == null || schemaText.isBlank()) return;
        try {
            JsonSchema schema = schemaCache.computeIfAbsent(schemaText, schemaFactory::getSchema);
            JsonNode node = mapper.valueToTree(arguments == null ? Map.of() : arguments);
            Set<ValidationMessage> errors = schema.validate(node);
            if (!errors.isEmpty()) {
                List<String> messages = new ArrayList<>();
                Map<String, String> details = new LinkedHashMap<>();
                for (ValidationMessage message : errors) {
                    messages.add(message.getMessage());
                    details.put(String.valueOf(message.getInstanceLocation()), message.getMessage());
                }
                throw new BizException(ErrorCode.AGENT_TOOL_ARGS_INVALID,
                        "工具参数校验失败：" + String.join("; ", messages), details)
                        .with("tool_name", definition.meta().name());
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // schema 本身写坏属于**服务端配置问题**，不能伪装成"用户参数错"
            throw new BizException(ErrorCode.AGENT_INTERNAL_ERROR,
                    "工具 schema 解析失败：" + definition.meta().name(), Map.of("tool_name", definition.meta().name()));
        }
    }

    /** 闸 3：敏感参数拦截（全局 + 工具级双重扫描） */
    public void checkSensitive(ToolDefinition definition, Map<String, Object> arguments) {
        List<Pattern> patterns = new ArrayList<>(GLOBAL_BLOCKED);
        for (String extra : definition.meta().sensitivePatterns()) {
            patterns.add(Pattern.compile(extra, Pattern.CASE_INSENSITIVE));
        }
        scan(arguments, patterns, definition.meta().name(), null);
    }

    /** SSRF 专用：HTTP 工具的 URL 参数不得指向内网 */
    public void checkNotInternalAddress(String url, String toolName) {
        scan(url, INTERNAL_ADDRESS, toolName, "SSRF_INTERNAL_ADDRESS");
    }

    private void scan(Object value, List<Pattern> patterns, String toolName, String fixedReason) {
        if (value == null) return;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                scan(entry.getValue(), patterns, toolName, fixedReason);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) scan(item, patterns, toolName, fixedReason);
            return;
        }
        if (!(value instanceof String text)) return;

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String hit = matcher.group();
                throw new BizException(ErrorCode.AGENT_TOOL_ARGS_BLOCKED,
                        "参数命中安全拦截规则：" + (fixedReason == null ? pattern.pattern() : fixedReason),
                        Map.of("tool_name", toolName,
                                "matched", hit.length() > 64 ? hit.substring(0, 64) : hit,
                                "rule", fixedReason == null ? pattern.pattern() : fixedReason))
                        .with("tool_name", toolName);
            }
        }
    }

    /**
     * 代码静态预检（R5-05「危险操作拦截」在沙箱前的第一道闸）：
     * 返回命中的规则列表，空列表代表通过。
     */
    public List<String> staticCodeScan(String code) {
        List<String> hits = new ArrayList<>();
        if (code == null) return hits;
        for (Pattern pattern : GLOBAL_BLOCKED) {
            Matcher matcher = pattern.matcher(code);
            if (matcher.find()) hits.add(pattern.pattern());
        }
        return hits;
    }
}