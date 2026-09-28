package com.ke.nhservice.aimianshi.graph;

/**
 * 图节点：吃进业务状态，吐出「继续」或「挂起」。
 * 引擎不认识 S 是什么，所以节点可以承载任意业务流程。
 */
@FunctionalInterface
public interface Node<S> {

    NodeResult execute(NodeContext ctx, S state);
}