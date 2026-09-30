package com.ke.nhservice.aimianshi.common.dto;

/**
 * 直接粘贴简历全文存一份，不经过 PDF。
 *
 * filename 可以空：粘进来的东西本来就没有文件名，服务端按时间给一个显示名。
 */
public record ResumeTextRequest(String filename, String content) {
}