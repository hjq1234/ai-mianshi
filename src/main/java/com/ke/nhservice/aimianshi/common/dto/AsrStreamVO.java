package com.ke.nhservice.aimianshi.common.dto;

/**
 * 流式那三个接口共用一个 VO：start 只填 sessionId，chunk 填 partial/finalText，
 * stop 只填 finalText。拆成三个 record 会多出两个只有一个字段的类型，
 * 而前端那边三个响应处理的是同一套字段名，共用一个反而省事。
 *
 * ★ JSON 里的字段名是 **finalText**，不是 final。Java 的 record 组件不能叫 final，
 *   而前端读的是 r.finalText —— 两边必须一致。写成 final 的话前端读 r.final 永远是
 *   undefined，而且**不报错**：表现就是「说完了字不进框」，很难往字段名上想。
 */
public record AsrStreamVO(String sessionId, String partial, String finalText) {

    public static AsrStreamVO started(String sessionId) {
        return new AsrStreamVO(sessionId, "", "");
    }

    public static AsrStreamVO chunk(String partial, String finalText) {
        return new AsrStreamVO(null, partial, finalText);
    }

    public static AsrStreamVO finished(String finalText) {
        return new AsrStreamVO(null, "", finalText);
    }
}