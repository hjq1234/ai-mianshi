package com.ke.nhservice.aimianshi.biz.interview.node;

import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.common.constant.Difficulty;
import com.ke.nhservice.aimianshi.graph.Node;
import com.ke.nhservice.aimianshi.graph.NodeContext;
import com.ke.nhservice.aimianshi.graph.NodeResult;

import java.util.function.Function;

/**
 * deepen / continue / lower / switch 四个分支节点合并成这一个类。
 *
 * 参考项目（Go 版）这四个节点是四个几乎重复的函数，约 80 行；
 * 这里靠「提示词构造 lambda + 难度增减量」两个参数合并，约 20 行。
 * 将来加新分支只需要一行 addNode + 一个提示词文件。
 */
public class SetHintNode implements Node<InterviewState> {

    private final Function<InterviewState, String> hintBuilder;

    /** +1 升档 / -1 降档 / 0 不变。Difficulty.shift 会自动夹紧边界 */
    private final int difficultyDelta;

    public SetHintNode(Function<InterviewState, String> hintBuilder) {
        this(hintBuilder, 0);
    }

    public SetHintNode(Function<InterviewState, String> hintBuilder, int difficultyDelta) {
        this.hintBuilder = hintBuilder;
        this.difficultyDelta = difficultyDelta;
    }

    @Override
    public NodeResult execute(NodeContext ctx, InterviewState state) {
        state.setNextActionHint(hintBuilder.apply(state));
        if (difficultyDelta != 0) {
            state.setCurrentDifficulty(
                    Difficulty.shift(state.getCurrentDifficulty(), difficultyDelta));
        }
        // 题号在这里递增。所以 EvaluateBranch 里「questionIndex >= maxQuestions」判断的是
        // 「刚答完的那题」，不是下一题——顺序不能颠倒。
        state.setQuestionIndex(state.getQuestionIndex() + 1);
        return NodeResult.NEXT;
    }
}