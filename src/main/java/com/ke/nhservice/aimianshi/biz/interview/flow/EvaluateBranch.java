package com.ke.nhservice.aimianshi.biz.interview.flow;

import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.graph.BranchCondition;

/**
 * 薄适配器：把 InterviewRouting 的决策函数接进图引擎的 BranchCondition 接口。
 * 图引擎不认识 InterviewState，只认 BranchCondition<String>，所以这层转发是必要的。
 *
 * 这里刻意只有一行转发——任何「顺便做点别的」都会让决策逻辑出现第二个来源，
 * 而 InterviewRouting 存在的全部意义就是「全图只有一处决策」。
 */
public class EvaluateBranch implements BranchCondition<InterviewState> {

    @Override
    public String decide(InterviewState state) {
        return InterviewRouting.decide(state);
    }
}