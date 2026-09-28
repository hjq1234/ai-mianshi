package com.ke.nhservice.aimianshi.biz.knowledge;

/** 检索到的一段上下文。一期不会产生实例，只定义形状 */
public record Chunk(String source, String content, double score) {
}