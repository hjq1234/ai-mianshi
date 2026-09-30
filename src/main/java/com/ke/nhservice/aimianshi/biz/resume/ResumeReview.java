package com.ke.nhservice.aimianshi.biz.resume;

/**
 * 一次简历改稿。t_resume_review 一行的 Java 形态。
 *
 * markdown 是**原始产物**（批注在原位）；页面上的「建议列表」和「干净参考稿」都是拿它现派生的，
 * 派生逻辑只有 ResumeReviewParser 一处，这个 record 不掺和。
 *
 * targetsJson / interviewIds 在这一层保持「库里的原样」（JSON 串、逗号分隔串）——
 * DAO 只管存取，拆开解释是 parser 和 service 的事。
 */
public record ResumeReview(Long id,
                           Long userId,
                           Long resumeId,
                           String markdown,
                           int suggestionCount,
                           String targetsJson,
                           String interviewIds,
                           String model,
                           boolean truncated,
                           long createdAt) {
}