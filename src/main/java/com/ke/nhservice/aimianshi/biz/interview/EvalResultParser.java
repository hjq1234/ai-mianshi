package com.ke.nhservice.aimianshi.biz.interview;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ke.nhservice.aimianshi.common.constant.NextAction;
import com.ke.nhservice.aimianshi.common.util.JsonUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 LLM 返回的评分 JSON 解析成 EvalResult。
 *
 * LLM 输出的不可控之处都要在这里兜住：
 * - 可能包在 ```json ``` 代码块里 → extractJson 剥围栏
 * - 前面可能带一句「好的，评估如下：」→ 截取第一个 { 到最后一个 }
 * - 分数可能超出 0-10 → clamp
 * - nextAction 可能拼错或漏给 → 按分数兜底（NextAction.from）
 * - 五个维度可能缺几个 → 缺的补 0（practice 除外，它允许为空，见 NULLABLE_DIMENSIONS）
 */
public final class EvalResultParser {

    private EvalResultParser() {
    }

    public static EvalResult parse(String raw) {
        LlmEvalDto dto = JsonUtil.fromJson(extractJson(raw), LlmEvalDto.class);

        EvalResult result = new EvalResult();
        result.setOverall(clamp(dto.overall()));
        result.setDimensions(normalizeDimensions(dto.dimensions()));
        result.setCoveredTopics(dto.coveredTopics() == null ? List.of() : dto.coveredTopics());
        result.setComment(dto.comment() == null ? "" : dto.comment());
        result.setNextAction(NextAction.from(dto.nextAction(), result.getOverall()));
        return result;
    }

    /** 截取第一个 { 到最后一个 }，顺带剥掉 markdown 围栏和前后的解释文字 */
    static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("模型返回为空");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException(
                    "模型返回中找不到 JSON 对象: " + JsonUtil.abbreviate(raw));
        }
        return raw.substring(start, end + 1);
    }

    private static double clamp(Double value) {
        if (value == null || value.isNaN()) {
            return 0;
        }
        return Math.max(0, Math.min(10, value));
    }

    /**
     * 永远返回完整的五个维度，顺序固定——前端雷达图直接按顺序画。
     * 可空维度（见 EvalResult.NULLABLE_DIMENSIONS）允许保持 null。
     */
    private static Map<String, Double> normalizeDimensions(Map<String, Double> raw) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String key : EvalResult.DIMENSIONS) {
            Double value = raw == null ? null : raw.get(key);
            // LLM 按 evaluate.md 的约定，遇到纯概念题会给 practice: null，这里必须原样保留；
            // 直接丢给 clamp 的话 null 会变成 0，那就成了「编一个分数出来」
            out.put(key, value == null && EvalResult.NULLABLE_DIMENSIONS.contains(key)
                    ? null : clamp(value));
        }
        return out;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LlmEvalDto(Double overall,
                      Map<String, Double> dimensions,
                      List<String> coveredTopics,
                      String comment,
                      String nextAction) {
    }
}