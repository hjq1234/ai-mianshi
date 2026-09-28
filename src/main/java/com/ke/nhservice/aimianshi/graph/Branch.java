package com.ke.nhservice.aimianshi.graph;

import java.util.Set;

/**
 * 分支定义：从一个节点出发，按条件路由到一组候选目标。
 * 包级私有——只给 Graph / CompiledGraph 用，不对外暴露。
 */
record Branch<S>(BranchCondition<S> condition, Set<String> targets) {
}