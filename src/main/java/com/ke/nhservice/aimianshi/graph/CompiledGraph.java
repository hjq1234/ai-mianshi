package com.ke.nhservice.aimianshi.graph;

import java.util.List;
import java.util.Map;

/**
 * 编译后的图。不可变，可以安全地被多线程共享。
 * 注意：节点实现本身也必须是无状态的。
 */
public class CompiledGraph<S> {

    private final Map<String, Node<S>> nodes;
    private final Map<String, String> edges;
    private final Map<String, Branch<S>> branches;
    private final List<GraphListener<S>> listeners;
    private final NodeContext context;
    private final String start;
    private final String end;
    private final int maxSteps;

    CompiledGraph(Map<String, Node<S>> nodes,
                  Map<String, String> edges,
                  Map<String, Branch<S>> branches,
                  List<GraphListener<S>> listeners,
                  NodeContext context,
                  String start,
                  String end,
                  int maxSteps) {
        this.nodes = Map.copyOf(nodes);
        this.edges = Map.copyOf(edges);
        this.branches = Map.copyOf(branches);
        this.listeners = List.copyOf(listeners);
        this.context = context;
        this.start = start;
        this.end = end;
        this.maxSteps = maxSteps;
    }

    public String getStart() { return start; }

    public String getEnd() { return end; }

    /**
     * 跑图。从 execution.cursor 开始（为 null 则从起始节点），
     * 遇到 Suspend / 终点 / 异常 / 超步数就返回，并把最新游标写回 execution。
     */
    public RunResult<S> run(Execution<S> execution) {
        S state = execution.getState();
        String cursor = execution.getCursor() != null ? execution.getCursor() : start;

        for (int step = 0; step < maxSteps; step++) {
            Node<S> node = nodes.get(cursor);
            if (node == null) {
                return RunResult.failed(cursor, state,
                        new GraphException("游标指向不存在的节点: " + cursor));
            }
            boolean terminal = cursor.equals(end);

            for (GraphListener<S> l : listeners) {
                l.onNodeEnter(cursor, state);
            }

            long startedAt = System.currentTimeMillis();
            NodeResult result;
            try {
                result = node.execute(context, state);
            } catch (Exception e) {
                long cost = System.currentTimeMillis() - startedAt;
                for (GraphListener<S> l : listeners) {
                    l.onNodeExit(cursor, null, cost, state);
                }
                execution.setCursor(cursor);
                return RunResult.failed(cursor, state, e);
            }
            long cost = System.currentTimeMillis() - startedAt;

            for (GraphListener<S> l : listeners) {
                l.onNodeExit(cursor, result, cost, state);
            }

            if (result instanceof NodeResult.Suspend) {
                for (GraphListener<S> l : listeners) {
                    l.onSuspend(cursor, state);
                }
                execution.setCursor(cursor);
                return RunResult.suspended(cursor, state);
            }

            // 终点节点也要执行（它负责生成报告），执行完才算结束
            if (terminal) {
                execution.setCursor(cursor);
                return RunResult.finished(cursor, state);
            }

            String next = resolveNext(cursor, state);
            if (branches.containsKey(cursor)) {
                for (GraphListener<S> l : listeners) {
                    l.onBranchDecided(cursor, next, state);
                }
            }
            cursor = next;
            execution.setCursor(cursor);
        }

        return RunResult.stepLimit(cursor, state);
    }

    private String resolveNext(String from, S state) {
        Branch<S> branch = branches.get(from);
        if (branch != null) {
            String decided = branch.condition().decide(state);
            if (!branch.targets().contains(decided)) {
                throw new GraphException(
                        "分支 " + from + " 返回了未声明的目标: " + decided
                                + "，已声明的目标为 " + branch.targets());
            }
            return decided;
        }
        String to = edges.get(from);
        if (to == null) {
            throw new GraphException("节点 " + from + " 没有出边也没有分支");
        }
        return to;
    }
}