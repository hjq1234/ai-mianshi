package com.ke.nhservice.aimianshi.biz.interview;

public record TraceRow(
        long seq,
        Integer round,
        String nodeName,
        String nodeType,
        String fromNode,
        String toNode,
        Long costMs,
        String status,
        String errorMsg,
        long createdAt) {
}