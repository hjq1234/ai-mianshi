package com.ke.nhservice.aimianshi.biz.interview.flow;

import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.biz.interview.node.EndNode;
import com.ke.nhservice.aimianshi.biz.interview.node.EvaluateNode;
import com.ke.nhservice.aimianshi.biz.interview.node.QuestionNode;
import com.ke.nhservice.aimianshi.biz.interview.node.SetHintNode;
import com.ke.nhservice.aimianshi.biz.interview.node.StartNode;
import com.ke.nhservice.aimianshi.biz.interview.node.WaitAnswerNode;
import com.ke.nhservice.aimianshi.biz.interview.prompt.PromptLoader;
import com.ke.nhservice.aimianshi.common.config.InterviewProperties;
import com.ke.nhservice.aimianshi.graph.CompiledGraph;
import com.ke.nhservice.aimianshi.graph.Graph;
import com.ke.nhservice.aimianshi.graph.GraphListener;
import com.ke.nhservice.aimianshi.graph.NodeContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 面试流程图。形状：
 *
 * <pre>
 * START
 *   │
 *   ▼
 * start ──────── 初始化：questionIndex = 1，灌入简历摘要与话题池
 *   │
 *   ▼
 * question ───── 调 LLM 出题（prompt = nextActionHint + 简历 + 最近 2 题历史）
 *   │
 *   ▼
 * wait_answer ── ★ 全图唯一挂起点
 *   │
 *   ▼
 * evaluate ───── 调 LLM 评分 → EvalResult（含 nextAction）
 *   │
 *   ├──[ EvaluateBranch ]──┬──→ deepen   ┐
 *   │                      ├──→ continue │
 *   │                      ├──→ lower    ├──→ question   ← 普通边，形成循环
 *   │                      ├──→ switch   ┘
 *   │                      └──→ end_loop ──→ END
 * </pre>
 *
 * 构建出来的 CompiledGraph 不可变、节点无状态，所以可以放心地做成单例复用。
 */
@Component
public class InterviewGraphFactory {

    public static final String NODE_START = "start";
    public static final String NODE_QUESTION = "question";
    public static final String NODE_WAIT_ANSWER = "wait_answer";
    public static final String NODE_EVALUATE = "evaluate";
    public static final String NODE_DEEPEN = InterviewRouting.DEEPEN;
    public static final String NODE_CONTINUE = InterviewRouting.CONTINUE;
    public static final String NODE_LOWER = InterviewRouting.LOWER;
    public static final String NODE_SWITCH = InterviewRouting.SWITCH;
    public static final String NODE_END = InterviewRouting.END;

    private final PromptLoader prompts;
    private final InterviewProperties props;

    public InterviewGraphFactory(PromptLoader prompts, InterviewProperties props) {
        this.prompts = prompts;
        this.props = props;
    }

    public CompiledGraph<InterviewState> build(NodeContext context,
                                               GraphListener<InterviewState> listener) {
        Graph<InterviewState> graph = new Graph<>();
        graph.context(context);

        graph.addNode(NODE_START, new StartNode());
        graph.addNode(NODE_QUESTION, new QuestionNode());
        graph.addNode(NODE_WAIT_ANSWER, new WaitAnswerNode());
        graph.addNode(NODE_EVALUATE, new EvaluateNode());

        graph.addNode(NODE_DEEPEN, new SetHintNode(state -> prompts.load("hint_deepen"), +1));
        graph.addNode(NODE_CONTINUE, new SetHintNode(state -> prompts.load("hint_continue"), 0));
        graph.addNode(NODE_LOWER, new SetHintNode(state -> prompts.load("hint_lower"), -1));
        graph.addNode(NODE_SWITCH, new SetHintNode(state -> {
            // 换话题的动作在这里做，但话题是 EvaluateNode 已经挑好、写进 state.nextTopic 的。
            // 这里只负责落到 currentTopic 上，★ 不要重新 suggestNextTopic() —— 那会算出
            // 和落库的 next_topic 不同的值，复盘时看到的话题就对不上了。
            String topic = state.getNextTopic();
            state.getTopicTracker().setCurrentTopic(topic);
            return prompts.render("hint_switch", Map.of("topic", topic == null ? "" : topic));
        }, 0));

        graph.addNode(NODE_END, new EndNode());

        graph.startAt(NODE_START);
        graph.addEdge(NODE_START, NODE_QUESTION);
        graph.addEdge(NODE_QUESTION, NODE_WAIT_ANSWER);
        graph.addEdge(NODE_WAIT_ANSWER, NODE_EVALUATE);
        graph.addBranch(NODE_EVALUATE, new EvaluateBranch(),
                Set.of(NODE_DEEPEN, NODE_CONTINUE, NODE_LOWER, NODE_SWITCH, NODE_END));
        graph.addEdge(NODE_DEEPEN, NODE_QUESTION);
        graph.addEdge(NODE_CONTINUE, NODE_QUESTION);
        graph.addEdge(NODE_LOWER, NODE_QUESTION);
        graph.addEdge(NODE_SWITCH, NODE_QUESTION);
        graph.endAt(NODE_END);

        if (listener != null) {
            graph.listener(listener);
        }
        return graph.compile(props.getMaxSteps());
    }
}