package com.ke.nhservice.aimianshi.common.dto;

import java.util.List;

/**
 * 生成一次改稿的请求体。
 *
 * interviewIds 可以空：不看面试记录也能改简历，只是建议里少一层「你实际答成什么样」的证据。
 */
public record ResumeReviewRequest(Long resumeId,
                                  List<ResumeReviewTarget> targets,
                                  List<Long> interviewIds) {
}