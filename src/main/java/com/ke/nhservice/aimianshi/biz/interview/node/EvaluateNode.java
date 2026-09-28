package com.ke.nhservice.aimianshi.biz.interview.node;

import com.ke.nhservice.aimianshi.biz.interview.Dialogue;
import com.ke.nhservice.aimianshi.biz.interview.EvalResult;
import com.ke.nhservice.aimianshi.biz.interview.EvalResultParser;
import com.ke.nhservice.aimianshi.biz.interview.HistoryItem;
import com.ke.nhservice.aimianshi.biz.interview.InterviewDao;
import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.biz.interview.TopicTracker;
import com.ke.nhservice.aimianshi.biz.interview.flow.InterviewRouting;
import com.ke.nhservice.aimianshi.biz.interview.prompt.PromptLoader;
import com.ke.nhservice.aimianshi.common.config.InterviewProperties;
import com.ke.nhservice.aimianshi.common.constant.NextAction;
import com.ke.nhservice.aimianshi.common.constant.RecordStatus;
import com.ke.nhservice.aimianshi.graph.Node;
import com.ke.nhservice.aimianshi.graph.NodeContext;
import com.ke.nhservice.aimianshi.graph.NodeResult;
import com.ke.nhservice.aimianshi.wrapper.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 评分。这是整张图信息最密集的节点，做了四件事：
 *
 * 1. 调 LLM 评分（失败则降级为 CONTINUE，不中断面试）
 * 2. 话题追踪（覆盖度、连续追问计数、必要时强制换话题）
 * 3. 落库 t_interview_dialogue（含图的分支决策）
 * 4. 清掉已消费的 answer，把本题推入滑动窗口
 *
 * 关于落库的 next_action：用的是 InterviewRouting.decide() —— 和紧接着执行的分支
 * 判断是同一个纯函数。此时 state 还没被分支节点改动，所以算出来的结果和分支
 * 实际走的路由必然一致，不会出现「DB 记着 deepen、实际走了 switch」。
 */
public class EvaluateNode implements Node<InterviewState> {

    private static final Logger log = LoggerFactory.getLogger(EvaluateNode.class);

    /** 滑动窗口保留的题数 */
    private static final int HISTORY_WINDOW = 2;

    @Override
    public NodeResult execute(NodeContext ctx, InterviewState state) {
        // 出题失败或用户已点结束，跳过评分直接收尾
        if (state.isShouldStop()) {
            return NodeResult.NEXT;
        }

        LlmClient llm = ctx.get(LlmClient.class);
        PromptLoader prompts = ctx.get(PromptLoader.class);
        InterviewDao dao = ctx.get(InterviewDao.class);
        InterviewProperties props = ctx.get(InterviewProperties.class);

        String question = state.getQuestionText();
        String answer = state.getAnswer();
        TopicTracker tracker = state.getTopicTracker();

        EvalResult result = evaluate(llm, prompts, state, question, answer);

        // 同一个话题追问太多次就强制换话题，不完全交给 LLM 判断
        if (tracker.getFollowUpCount() >= props.getMaxFollowUp()) {
            log.debug("话题「{}」已连续追问 {} 次，强制换话题",
                    tracker.getCurrentTopic(), tracker.getFollowUpCount());
            result.setNextAction(NextAction.SWITCH);
        }
        state.setEvalResult(result);

        String routing = InterviewRouting.decide(state);
        state.setLastRouting(routing);

        String nextTopic = "switch".equals(routing) ? tracker.suggestNextTopic() : null;

        Dialogue dialogue = new Dialogue();
        dialogue.setSeq(state.getQuestionIndex());
        dialogue.setTopic(tracker.getCurrentTopic());
        dialogue.setDifficulty(state.getCurrentDifficulty().getLabel());
        dialogue.setQuestion(question);
        dialogue.setAnswer(answer);
        dialogue.setScore(result.getOverall());
        dialogue.setDimensions(result.getDimensions());
        dialogue.setComment(result.getComment());
        // 落库时把 end_loop 写成 "end"，复盘表格里读起来更自然
        dialogue.setNextAction(InterviewRouting.END.equals(routing)
                ? RecordStatus.NEXT_ACTION_END : routing);
        dialogue.setNextTopic(nextTopic);
        dao.upsertDialogue(dialogue, state.getRecordId());

        state.getDialogues().add(dialogue);
        state.getScoreHistory().add(result.getOverall());
        state.pushHistory(new HistoryItem(question, answer, result.getOverall()), HISTORY_WINDOW);
        tracker.markCovered(tracker.getCurrentTopic());
        tracker.setFollowUpCount(result.getNextAction() == NextAction.DEEPEN
                ? tracker.getFollowUpCount() + 1 : 0);

        // 答案已消费，清掉。这样下一轮回到 wait_answer 才会正确挂起
        state.setAnswer(null);
        return NodeResult.NEXT;
    }

    private EvalResult evaluate(LlmClient llm, PromptLoader prompts, InterviewState state,
                                String question, String answer) {
        Map<String, String> vars = new HashMap<>();
        vars.put("position", orEmpty(state.getPosition()));
        vars.put("domain", orEmpty(state.getDomain()));
        vars.put("topic", orEmpty(state.getTopicTracker().getCurrentTopic()));
        vars.put("difficulty", state.getCurrentDifficulty().getLabel());
        vars.put("question", orEmpty(question));
        vars.put("answer", orEmpty(answer));

        try {
            return EvalResultParser.parse(llm.chat(prompts.render("evaluate", vars)));
        } catch (Exception e) {
            // 评分失败不中断面试：本题按 0 分记、下一步按 CONTINUE 走，
            // 候选人还能继续答后面的题，已经答过的几题也都保住了
            log.warn("第 {} 题评分失败，降级为 CONTINUE: {}", state.getQuestionIndex(), e.getMessage());
            return EvalResult.degraded(e.getMessage());
        }
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }
}