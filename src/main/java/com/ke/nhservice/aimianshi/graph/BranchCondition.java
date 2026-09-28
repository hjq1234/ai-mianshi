package com.ke.nhservice.aimianshi.graph;

/** 条件分支：给定状态，返回下一个节点名 */
@FunctionalInterface
public interface BranchCondition<S> {

    String decide(S state);
}