package com.ke.nhservice.aimianshi.biz.interview.flow;

import com.ke.nhservice.aimianshi.biz.interview.EvalResult;
import com.ke.nhservice.aimianshi.biz.interview.InterviewState;

/**
 * ★ 全图唯一的决策点，纯函数（不碰数据库、不调 LLM）。
 *
 * 抽成独立静态函数而不是写在 EvaluateBranch 里，是因为 EvaluateNode 落库
 * next_action 时要用同一套判断。两处各写一份，迟早会出现「数据库记录的分支
 * 和实际走的分支不一致」。
 *
 * 题数检查放在这里（evaluate 之后）而不是 question 之前：
 * 此时 questionIndex 恰好是刚答完那题的编号，而 deepen 等分支节点只负责 +1、
 * 不设 shouldStop，所以回到 question 时无需再判断——不存在 off-by-one。
 */
public final class InterviewRouting {

    public static final String END = "end_loop";
    public static final String DEEPEN = "deepen";
    public static final String CONTINUE = "continue";
    public static final String LOWER = "lower";
    public static final String SWITCH = "switch";

    private InterviewRouting() {
    }

    public static String decide(InterviewState state) {
        if (state.isShouldStop()) {
            return END;
        }
        if (state.getQuestionIndex() >= state.getMaxQuestions()) {
            return END;
        }
        EvalResult result = state.getEvalResult();
        if (result == null || result.getNextAction() == null) {
            return CONTINUE;
        }
        return switch (result.getNextAction()) {
            case DEEPEN -> DEEPEN;
            case LOWER -> LOWER;
            case SWITCH -> SWITCH;
            case CONTINUE -> CONTINUE;
        };
    }
}