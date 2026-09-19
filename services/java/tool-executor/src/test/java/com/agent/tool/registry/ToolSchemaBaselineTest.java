package com.agent.tool.registry;

import com.agent.tool.tools.CalculatorTool;
import com.agent.tool.tools.CodeTool;
import com.agent.tool.tools.HttpTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IN-06 契约基线守卫（验收第 3 条：「schema 变更触发 L1 契约测试」）。
 *
 * <p>本用例是那条**触发线**的落点：指纹一旦对不上就红，逼迫改动方走一遍
 * 「重跑契约门禁 → 评估影响面（{@code GET /tools/{name}/impact}）→ 更新基线」的流程。
 *
 * <p>为什么由 Java 而不是 Python 门禁来比对指纹：指纹算法在
 * {@link ToolRegistry#fingerprint}，只有 JVM 能算出真值；让 Python 复制一份哈希实现
 * 等于制造"第二份真相"（本项目已因同类问题吃过亏）。故分工是：
 * <ul>
 *   <li>**Java（本用例）**：指纹与版本逐条比对，唯一权威；</li>
 *   <li>**Python（`contract-check.py`）**：基线文件存在性与"工具名集合"比对，
 *       防止新增/改名工具后忘记登记基线（跨语言跑不了哈希，就跑集合）。</li>
 * </ul>
 */
class ToolSchemaBaselineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Path baselineFile() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contracts").resolve("tool-schema-baseline.json");
            if (Files.exists(candidate)) return candidate;
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到 contracts/tool-schema-baseline.json（工作目录：" + Paths.get("").toAbsolutePath() + "）");
    }

    private Map<String, String> baselineHashes() throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(baselineFile()));
        Map<String, String> hashes = new LinkedHashMap<>();
        root.path("tools").fields().forEachRemaining(entry ->
                hashes.put(entry.getKey(), entry.getValue().path("schema_hash").asText()));
        return hashes;
    }

    @Test
    void baselineCoversExactlyTheBuiltinTools() throws Exception {
        Set<String> baseline = new TreeSet<>(baselineHashes().keySet());
        Set<String> builtin = new TreeSet<>(Set.of("calculator", "http", "code"));
        assertEquals(builtin, baseline,
                "基线登记的工具集合必须与内置工具集合一致；"
                        + "新增/删除工具后请同步 contracts/tool-schema-baseline.json（双向比对）");
    }

    @Test
    void everySchemaHashMatchesTheBaseline() throws Exception {
        Map<String, String> baseline = baselineHashes();

        assertFingerprint("calculator", CalculatorTool.schema(), baseline);
        assertFingerprint("http", HttpTool.schema(), baseline);
        assertFingerprint("code", CodeTool.schema(), baseline);
    }

    /**
     * 基线是**冻结的契约**：指纹不同说明 schema 变了。
     * 此时正确动作不是"顺手改基线"，而是先评估影响面（谁在消费这个工具）、
     * 回归其 L1 契约测试，再把新指纹写回基线。
     */
    private void assertFingerprint(String name, String schema, Map<String, String> baseline) {
        String actual = ToolRegistry.fingerprint(schema);
        String expected = baseline.get(name);
        assertTrue(expected != null && !expected.isBlank(),
                "基线缺少 " + name + " 的 schema_hash");
        assertEquals(expected, actual,
                "工具 " + name + " 的 schema 指纹与契约基线不一致 —— "
                        + "schema 已变更，请先跑 L1 契约测试与影响分析（GET /tools/" + name + "/impact），再更新 contracts/tool-schema-baseline.json");
    }
}
