package com.ke.nhservice.aimianshi.biz.resume;

public record Resume(Long id, Long userId, String filename, String content, boolean isDefault, long createdAt) {

    /** 列表展示用的摘要：去掉换行、截断，避免前端渲染出一大坨 */
    public String preview() {
        if (content == null) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
    }
}