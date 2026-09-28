package com.ke.nhservice.aimianshi.biz.interview.node;

import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.graph.Node;
import com.ke.nhservice.aimianshi.graph.NodeContext;
import com.ke.nhservice.aimianshi.graph.NodeResult;

/**
 * ★ 全图唯一的挂起点。
 *
 * 这个节点不「等」——它看状态决定能不能往下走，不能就返回 Suspend，
 * 引擎存完现场直接退出。请求线程立刻释放，没有线程被占住，也不需要心跳。
 *
 * 天然幂等：判断依据是「answer 是否为空」而不是计数器，重复执行结果一样。
 */
public class WaitAnswerNode implements Node<InterviewState> {

    @Override
    public NodeResult execute(NodeContext ctx, InterviewState state) {
        // 出题已经失败了，别再等答案，直接往下走让 evaluate 跳过、分支路由到 end 收尾
        if (state.isShouldStop()) {
            return NodeResult.NEXT;
        }
        String answer = state.getAnswer();
        if (answer == null || answer.isBlank()) {
            return NodeResult.SUSPEND;
        }
        return NodeResult.NEXT;
    }
}