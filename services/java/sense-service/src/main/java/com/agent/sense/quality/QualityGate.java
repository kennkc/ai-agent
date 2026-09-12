package com.agent.sense.quality;

import com.agent.sense.config.SenseProperties;
import com.agent.sense.model.CollectedData;
import org.springframework.stereotype.Component;

/**
 * 质检门槛（R2-10 隔离暂存前置质检 / R2-04 数据质量）
 *
 * 两层判定：
 *  1) 硬规则（rejectReason）：空内容 / 编码乱码 / 重复乱码 / 长度不足 → 直接拦截；
 *  2) 软评分（score）：非空、长度、可打印占比、信息密度加权，用于质量排序与阈值兜底。
 * 判定入口统一为 rejectReason（pass/apply 均以其为准），避免出现「评分高但内容不可用」的错放。
 */
@Component
public class QualityGate {

    private static final double W_CONTENT = 0.45;
    private static final double W_LENGTH = 0.20;
    private static final double W_PRINTABLE = 0.20;
    private static final double W_DENSITY = 0.15;

    private final SenseProperties properties;

    public QualityGate(SenseProperties properties) { this.properties = properties; }

    public double score(String content) {
        if (content == null || content.isBlank()) return 0.0;
        String text = content.strip();
        if (text.length() < properties.getQuality().getMinLength()) return 0.0;
        if (isRepetitiveGarbage(text)) return 0.0;
        double s = W_CONTENT
                + W_LENGTH * lengthFactor(text)
                + W_PRINTABLE * printableRatio(text)
                + W_DENSITY * informationDensity(text);
        return Math.round(Math.min(1.0, s) * 10000) / 10000.0;
    }

    /** 判定入口：与 rejectReason 保持单一事实来源 */
    public boolean pass(String content) {
        return rejectReason(content) == null;
    }

    public String rejectReason(String content) {
        if (content == null || content.isBlank()) return "EMPTY_CONTENT";
        String text = content.strip();
        if (printableRatio(text) < properties.getQuality().getMinPrintableRatio()) return "ENCODING_GARBLED";
        if (isRepetitiveGarbage(text)) return "REPETITIVE_GARBAGE";
        if (text.length() < properties.getQuality().getMinLength()) return "BELOW_QUALITY_THRESHOLD";
        if (score(text) < properties.getQuality().getThreshold()) return "BELOW_QUALITY_THRESHOLD";
        return null;
    }

    /** 对标准化后的采集结果执行质检并落状态 */
    public CollectedData apply(CollectedData data) {
        double score = score(data.getContent());
        String reason = rejectReason(data.getContent());
        if (reason == null) {
            data.setQualityScore(score);
            data.setStagingStatus(CollectedData.StagingStatus.ACCEPTED);
            data.setRejectReason(null);
        } else {
            data.setQualityScore(score);
            data.setStagingStatus(CollectedData.StagingStatus.REJECTED);
            data.setRejectReason(reason);
        }
        return data;
    }

    /** 长度因子：达到 minLength 记 1.0，超出部分饱和 */
    private double lengthFactor(String text) {
        int min = Math.max(1, properties.getQuality().getMinLength());
        return Math.min(1.0, (double) text.length() / min);
    }

    static double printableRatio(String text) {
        int total = 0;
        int printable = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            total++;
            if (c == '\n' || c == '\r' || c == '\t' || !Character.isISOControl(c)) printable++;
        }
        return total == 0 ? 0 : (double) printable / total;
    }

    static boolean isRepetitiveGarbage(String text) {
        if (text.length() < 12) return false;
        int maxRun = 1;
        int run = 1;
        for (int i = 1; i < text.length(); i++) {
            if (text.charAt(i) == text.charAt(i - 1)) {
                run++;
                maxRun = Math.max(maxRun, run);
            } else {
                run = 1;
            }
        }
        if (maxRun >= 12) return true;
        // 单字符种类过少（如 "abababab..."）
        long distinct = text.chars().filter(c -> !Character.isWhitespace(c)).distinct().count();
        return text.length() >= 40 && distinct <= 2;
    }

    static double informationDensity(String text) {
        int meaningful = 0;
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            total++;
            if (Character.isLetterOrDigit(c)) meaningful++;
        }
        return total == 0 ? 0 : Math.min(1.0, (double) meaningful / total);
    }
}
