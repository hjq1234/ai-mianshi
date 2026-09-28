package com.ke.nhservice.aimianshi.biz.interview;

/**
 * t_interview_record 一行的扁平形态。
 * 和 InterviewState 分开：前者是「记录元信息 + 引擎快照」，后者是「业务现场」。
 */
public record RecordRow(
        Long id,
        Long userId,
        Long resumeId,
        String position,
        String company,
        String domain,
        String difficulty,
        String status,
        Double totalScore,
        String report,
        String stateJson,
        String cursor,
        long createdAt,
        long updatedAt,
        String resumeSummary) {
}