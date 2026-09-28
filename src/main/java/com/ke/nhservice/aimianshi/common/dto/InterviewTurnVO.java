package com.ke.nhservice.aimianshi.common.dto;

import java.util.Map;

/**
 * 一次「推进面试」的结果。
 *
 * 四个接口返回同一个形状，前端只需要一套渲染逻辑：
 * - questionIndex / total  → 顶部进度「第 3/10 题」
 * - lastScore / lastComment → 上一题的评分反馈
 * - nextAction             → 上一题走的分支（deepen/continue/lower/switch/end）
 * - question               → 当前要回答的问题
 * - finished + report      → 结束了，跳复盘页
 */
public record InterviewTurnVO(
        Long recordId,
        String status,
        boolean finished,
        Integer questionIndex,
        Integer total,
        String question,
        String topic,
        String difficulty,
        Double lastScore,
        Map<String, Double> lastDimensions,
        String lastComment,
        String nextAction,
        Double averageScore,
        String report,
        String error) {
}