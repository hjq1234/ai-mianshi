package com.ke.nhservice.aimianshi.common.constant;

/**
 * 图在评分之后要走的下一步。由 LLM 判断，判断不出来时按分数兜底。
 */
public enum NextAction {

    /** 答得好，往深里追 */
    DEEPEN,

    /** 中等，同话题换个角度 */
    CONTINUE,

    /** 答得差，降难度 */
    LOWER,

    /** 话题聊透了，换新话题 */
    SWITCH;

    /**
     * 解析 LLM 返回的 nextAction。
     * LLM 没给、给了错拼、给了别的词，一律按分数兜底，不抛异常——
     * 评分字段解析失败不该让整场面试挂掉。
     */
    public static NextAction from(String raw, double score) {
        if (raw != null && !raw.isBlank()) {
            String v = raw.trim().toUpperCase();
            for (NextAction a : values()) {
                if (a.name().equals(v)) {
                    return a;
                }
            }
        }
        return infer(score);
    }

    /** 分数兜底规则（设计文档 5.3） */
    public static NextAction infer(double score) {
        if (score >= 8.0) {
            return DEEPEN;
        }
        if (score < 4.0) {
            return LOWER;
        }
        return CONTINUE;
    }

    /** 落库用的小写形式：deepen / continue / lower / switch */
    public String code() {
        return name().toLowerCase();
    }
}