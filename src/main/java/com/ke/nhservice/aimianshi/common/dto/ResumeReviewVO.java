package com.ke.nhservice.aimianshi.common.dto;

import com.ke.nhservice.aimianshi.biz.resume.ResumeReviewParser;

import java.util.List;

/**
 * 一次改稿的详情。
 *
 * markdown / document / suggestions 是**同一份产物的三个视图**，都不落库、每次现派生
 * （派生逻辑只有 ResumeReviewParser 一处）：
 *   markdown    原始全文，批注在原位 —— **导出用这个**
 *   document    去掉批注的干净正文 —— 页面渲染参考稿用这个
 *   suggestions 抽出来的建议列表（带分组）
 */
public record ResumeReviewVO(Long id,
                             Long resumeId,
                             String filename,
                             long createdAt,
                             String model,
                             boolean truncated,
                             List<ResumeReviewTarget> targets,
                             List<Long> interviewIds,
                             String markdown,
                             String document,
                             List<ResumeReviewParser.Suggestion> suggestions) {
}