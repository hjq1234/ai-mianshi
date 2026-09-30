package com.ke.nhservice.aimianshi.common.dto;

import java.util.List;

/**
 * 历史列表里的一行。
 *
 * ★ 不含 markdown：一行的全文可能上万字，而列表只要元信息。
 *   要全文点「查看」走 /api/resume-review/{id}。
 */
public record ResumeReviewItemVO(Long id,
                                 long createdAt,
                                 int suggestionCount,
                                 List<ResumeReviewTarget> targets,
                                 int interviewCount,
                                 String model,
                                 boolean truncated) {
}