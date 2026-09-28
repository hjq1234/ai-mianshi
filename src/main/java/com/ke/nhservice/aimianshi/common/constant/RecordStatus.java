package com.ke.nhservice.aimianshi.common.constant;

public enum RecordStatus {

    /** 面试进行中。关页面、服务重启、LLM 挂了，都是这个状态，随时可继续 */
    IN_PROGRESS("in_progress"),

    /** 已结束，报告已生成 */
    FINISHED("finished");

    private final String code;

    RecordStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static RecordStatus fromCode(String code) {
        for (RecordStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        return IN_PROGRESS;
    }

    /** 落库用：t_interview_dialogue.next_action 里 "end" 不算 NextAction，单独定义 */
    public static final String NEXT_ACTION_END = "end";
}