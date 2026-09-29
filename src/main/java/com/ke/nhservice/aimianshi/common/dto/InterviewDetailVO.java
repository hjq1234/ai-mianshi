package com.ke.nhservice.aimianshi.common.dto;

import java.util.List;
import java.util.Map;

/**
 * 复盘详情。一期只用 dialogues + report；
 * traces 是一期就采全的数据，二期画「图执行路径可视化」时直接用。
 */
public record InterviewDetailVO(
        Long id,
        String position,
        String company,
        String domain,
        String difficulty,
        String status,
        Double totalScore,
        String report,
        String error,
        long createdAt,
        long updatedAt,
        // 本场五维均分，雷达图直接用。★ 由后端的 InterviewStats 算，前端不重写一遍
        // 「null 维度不进分母」那条规则——两边各写一份迟早会对不上
        Map<String, Double> dimensionAverages,
        List<DialogueVO> dialogues,
        List<TraceVO> traces) {

    public record DialogueVO(
            int seq,
            String topic,
            String difficulty,
            String question,
            String answer,
            Double score,
            Map<String, Double> dimensions,
            String comment,
            String nextAction,
            String nextTopic) {
    }

    public record TraceVO(
            long seq,
            Integer round,
            String nodeName,
            String nodeType,
            String fromNode,
            String toNode,
            Long costMs,
            String status) {
    }
}