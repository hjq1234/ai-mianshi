package com.ke.nhservice.aimianshi.biz.interview.trace;

import com.ke.nhservice.aimianshi.biz.interview.InterviewDao;
import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.graph.GraphListener;
import com.ke.nhservice.aimianshi.graph.NodeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把图的执行轨迹写进 t_graph_trace。
 *
 * 这是「图引擎不依赖业务」这条约束的验证点：引擎只管在关键时刻回调，
 * 记录什么、往哪记，全在这个业务侧的实现里。
 *
 * 每个节点执行写一行（node_type = normal / suspend），每次分支决策再写一行
 * （node_type = branch，to_node 是决策结果）。所以 evaluate 那一轮会有两行：
 * 一行是节点本身，一行是它做出的分支决策。
 */
@Component
public class TraceRecorder implements GraphListener<InterviewState> {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);

    private final InterviewDao dao;

    public TraceRecorder(InterviewDao dao) {
        this.dao = dao;
    }

    @Override
    public void onNodeExit(String node, NodeResult result, long costMs, InterviewState state) {
        if (state.getRecordId() == null) {
            return;
        }
        String nodeType;
        String status;
        if (result == null) {
            nodeType = "normal";
            status = "error";
        } else if (result instanceof NodeResult.Suspend) {
            nodeType = "suspend";
            status = "suspend";
        } else {
            nodeType = "normal";
            status = "ok";
        }
        write(state, node, nodeType, null, null, costMs, status, null);
    }

    @Override
    public void onBranchDecided(String from, String decided, InterviewState state) {
        if (state.getRecordId() == null) {
            return;
        }
        write(state, from, "branch", from, decided, 0L, "ok", null);
    }

    /** 轨迹写失败不能影响面试本身——它只是观察者 */
    private void write(InterviewState state, String nodeName, String nodeType,
                       String fromNode, String toNode, long costMs, String status, String errorMsg) {
        try {
            dao.insertTrace(state.getRecordId(), state.getQuestionIndex(), nodeName, nodeType,
                    fromNode, toNode, costMs, status, errorMsg);
        } catch (Exception e) {
            log.warn("写图执行轨迹失败（不影响面试）: {}", e.getMessage());
        }
    }
}