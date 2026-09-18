package com.agent.body.chunk;

import com.agent.body.common.BizException;
import com.agent.body.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档分块器（R3-02）——与 Python 侧 {@code app/chunking.py} 保持同算法，
 * 跨语言一致性由 {@code POST /api/nlp/chunk} 对照校验。
 *
 * <p>策略：标题（{@code #}~{@code ######}）切小节 → 空行切段落 → 累加至 maxChars（默认 800）
 * → 同小节小块合并 → 块首携带标题（保留上下文归属）→ 相邻块保留 overlap（默认 50）尾巴。
 *
 * <p>验收口径：分块无遗漏（正文关键词全部保留）、块大小不超 maxChars + 标题 + overlap。
 */
@Component
public class ChunkProcessor {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?；;\n]");

    private final int maxChars;
    private final int minChars;
    private final int overlap;

    public ChunkProcessor(@Value("${app.body.chunk.max-chars:800}") int maxChars,
                          @Value("${app.body.chunk.min-chars:200}") int minChars,
                          @Value("${app.body.chunk.overlap:50}") int overlap) {
        if (maxChars <= 0 || minChars < 0 || overlap < 0 || minChars > maxChars || overlap >= maxChars) {
            throw new IllegalArgumentException("invalid chunking parameters");
        }
        this.maxChars = maxChars;
        this.minChars = minChars;
        this.overlap = overlap;
    }

    public List<Chunk> chunk(String content) {
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "content must not be blank");
        }
        List<Section> sections = splitSections(content);
        List<Piece> pieces = new ArrayList<>();
        for (Section section : sections) {
            List<String> paragraphs = splitParagraphs(section.body());
            if (paragraphs.isEmpty()) {
                paragraphs = List.of(section.body());
            }
            for (String paragraph : paragraphs) {
                for (String piece : splitLongParagraph(paragraph)) {
                    pieces.add(new Piece(section.heading(), piece));
                }
            }
        }
        List<Piece> blocks = accumulate(pieces);
        List<Piece> merged = mergeSmallBlocks(blocks);
        return decorateAndOverlap(merged);
    }

    private List<Section> splitSections(String content) {
        List<Section> sections = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String heading = "";
        for (String rawLine : content.replace("\r\n", "\n").split("\n", -1)) {
            Matcher matcher = HEADING.matcher(rawLine.trim());
            if (matcher.matches()) {
                flushSection(sections, heading, buffer);
                heading = matcher.group(2).trim();
                continue;
            }
            buffer.append(rawLine).append('\n');
        }
        flushSection(sections, heading, buffer);
        if (sections.isEmpty() && !content.isBlank()) {
            sections.add(new Section("", content.trim()));
        }
        return sections;
    }

    private void flushSection(List<Section> sections, String heading, StringBuilder buffer) {
        String body = buffer.toString().trim();
        buffer.setLength(0);
        if (!body.isBlank()) {
            sections.add(new Section(heading, body));
        }
    }

    private List<String> splitParagraphs(String body) {
        List<String> paragraphs = new ArrayList<>();
        for (String block : body.split("\n\\s*\n")) {
            String value = block.trim();
            if (!value.isBlank()) paragraphs.add(value);
        }
        return paragraphs;
    }

    /** 超长段落按句末标点切分；无标点时硬切（保证不遗漏） */
    private List<String> splitLongParagraph(String paragraph) {
        if (paragraph.length() <= maxChars) {
            return List.of(paragraph);
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < paragraph.length()) {
            int end = Math.min(start + maxChars, paragraph.length());
            if (end < paragraph.length()) {
                Matcher matcher = SENTENCE_END.matcher(paragraph.substring(start, end));
                int lastEnd = -1;
                while (matcher.find()) {
                    lastEnd = matcher.end();
                }
                if (lastEnd > 0 && lastEnd >= maxChars / 2) {
                    end = start + lastEnd;
                }
            }
            String piece = paragraph.substring(start, end).trim();
            if (!piece.isBlank()) pieces.add(piece);
            start = end;
        }
        return pieces;
    }

    private List<Piece> accumulate(List<Piece> pieces) {
        List<Piece> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentHeading = null;
        for (Piece piece : pieces) {
            boolean headingChanged = currentHeading != null && !currentHeading.equals(piece.heading());
            if (current.length() > 0 && (headingChanged || current.length() + piece.text().length() + 1 > maxChars)) {
                blocks.add(new Piece(currentHeading, current.toString().trim()));
                current.setLength(0);
            }
            currentHeading = piece.heading();
            if (current.length() > 0) current.append('\n');
            current.append(piece.text());
        }
        if (current.length() > 0) {
            blocks.add(new Piece(currentHeading, current.toString().trim()));
        }
        return blocks;
    }

    /** 过小块与**同标题**相邻块合并；跨小节不合并（避免破坏语义边界与 heading 归属） */
    private List<Piece> mergeSmallBlocks(List<Piece> blocks) {
        List<Piece> merged = new ArrayList<>();
        for (Piece block : blocks) {
            Piece last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            boolean sameSection = last != null && java.util.Objects.equals(last.heading(), block.heading());
            if (sameSection && block.text().length() < minChars
                    && last.text().length() + block.text().length() + 1 <= maxChars) {
                merged.set(merged.size() - 1, new Piece(last.heading(), last.text() + "\n" + block.text()));
            } else {
                merged.add(block);
            }
        }
        return merged;
    }

    /**
     * 块首组装：{@code [标题] → [上块重叠尾巴] → [本块正文]}。
     *
     * <p>顺序很关键——标题必须在**最前**，保证「块首携带标题」这一验收口径成立；
     * 重叠尾巴置于标题之后，避免把上一小节的标题顶到块首造成归属混淆。
     */
    private List<Chunk> decorateAndOverlap(List<Piece> blocks) {
        List<Chunk> chunks = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            Piece block = blocks.get(index);
            String heading = block.heading() == null ? "" : block.heading();
            String overlapTail = "";
            if (index > 0 && overlap > 0) {
                String previousBody = blocks.get(index - 1).text();
                overlapTail = previousBody.length() <= overlap
                        ? previousBody : previousBody.substring(previousBody.length() - overlap);
            }
            StringBuilder content = new StringBuilder();
            if (!heading.isBlank()) {
                content.append(heading).append('\n');
            }
            if (!overlapTail.isBlank()) {
                content.append(overlapTail).append('\n');
            }
            content.append(block.text());
            chunks.add(new Chunk(index, heading, content.toString()));
        }
        return chunks;
    }

    private record Section(String heading, String body) { }

    private record Piece(String heading, String text) { }

    /** 分块结果：index 为块序号，heading 为所属小节标题，content 已携带标题与重叠尾巴 */
    public record Chunk(int index, String heading, String content) { }
}
