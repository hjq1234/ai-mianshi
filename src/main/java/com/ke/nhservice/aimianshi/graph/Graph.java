package com.ke.nhservice.aimianshi.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图构建器。链式调用，compile() 时做一次完整校验。
 */
public class Graph<S> {

    private final Map<String, Node<S>> nodes = new LinkedHashMap<>();
    private final Map<String, String> edges = new LinkedHashMap<>();
    private final Map<String, Branch<S>> branches = new LinkedHashMap<>();
    private final List<GraphListener<S>> listeners = new ArrayList<>();
    private NodeContext context = new NodeContext();
    private String start;
    private String end;

    public Graph<S> addNode(String name, Node<S> node) {
        if (nodes.putIfAbsent(name, node) != null) {
            throw new GraphException("节点名重复: " + name);
        }
        return this;
    }

    /** 无条件边 */
    public Graph<S> addEdge(String from, String to) {
        edges.put(from, to);
        return this;
    }

    /** 条件分支：从 from 出发，可路由到 targets 中的任意一个 */
    public Graph<S> addBranch(String from, BranchCondition<S> condition, Set<String> targets) {
        branches.put(from, new Branch<>(condition, Set.copyOf(targets)));
        return this;
    }

    public Graph<S> startAt(String name) {
        this.start = name;
        return this;
    }

    /** 结束节点：执行完它之后图返回 FINISHED */
    public Graph<S> endAt(String name) {
        this.end = name;
        return this;
    }

    public Graph<S> context(NodeContext context) {
        this.context = context;
        return this;
    }

    public Graph<S> listener(GraphListener<S> listener) {
        this.listeners.add(listener);
        return this;
    }

    /**
     * @param maxSteps 单次 run 的最大步数，防死循环
     */
    public CompiledGraph<S> compile(int maxSteps) {
        if (start == null) {
            throw new GraphException("未指定起始节点，请调用 startAt()");
        }
        if (end == null) {
            throw new GraphException("未指定结束节点，请调用 endAt()");
        }
        if (!nodes.containsKey(start)) {
            throw new GraphException("起始节点不存在: " + start);
        }
        if (!nodes.containsKey(end)) {
            throw new GraphException("结束节点不存在: " + end);
        }

        for (String name : nodes.keySet()) {
            boolean hasEdge = edges.containsKey(name);
            boolean hasBranch = branches.containsKey(name);
            if (hasEdge && hasBranch) {
                throw new GraphException("节点 " + name + " 同时定义了边和分支，只能二选一");
            }
            if (name.equals(end)) {
                continue;   // 终点，允许没有出边
            }
            if (!hasEdge && !hasBranch) {
                throw new GraphException("节点 " + name + " 既没有出边也没有分支，会成为死胡同");
            }
        }

        edges.forEach((from, to) -> {
            if (!nodes.containsKey(to)) {
                throw new GraphException("边 " + from + " -> " + to + " 的目标节点不存在");
            }
        });

        branches.forEach((from, branch) -> branch.targets().forEach(target -> {
            if (!nodes.containsKey(target)) {
                throw new GraphException("分支 " + from + " 的目标节点不存在: " + target);
            }
        }));

        return new CompiledGraph<>(nodes, edges, branches, listeners, context, start, end, maxSteps);
    }
}