package com.ke.nhservice.aimianshi.graph;

/**
 * 执行轨迹钩子。全部 default 空实现，业务层只覆写关心的那几个。
 * 用接口反转依赖：引擎不需要知道「记录轨迹」这件事存在。
 */
public interface GraphListener<S> {

    default void onNodeEnter(String node, S state) {}

    /** result 为 null 表示节点抛了异常 */
    default void onNodeExit(String node, NodeResult result, long costMs, S state) {}

    default void onBranchDecided(String from, String decided, S state) {}

    default void onSuspend(String node, S state) {}
}