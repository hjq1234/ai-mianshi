package com.ke.nhservice.aimianshi.common.dto;

/**
 * 一个目标岗位：岗位名 + 可空的 JD。
 *
 * 既是请求体（{@link ResumeReviewRequest}）里的一项，也是 targets_json 里的一项——
 * 形状一样就不定义两份。
 */
public record ResumeReviewTarget(String title, String jd) {
}