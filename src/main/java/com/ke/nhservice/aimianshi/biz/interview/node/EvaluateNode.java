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
 * 2. 话题追踪（覆盖度、本话题已答轮数、问满则强制换话题）
 * 3. 落库 t_interview_dialogue（含图的分支决策）
 * 4. 清掉已消费的 answer，把本题推入滑动窗口
 * 5. switch 路由下挑好下一个话题写进 state.nextTopic，交给 switch 分支节点执行
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

        long startedAt = System.currentTimeMillis();
        EvalResult result = evaluate(llm, prompts, props, state, question, answer);
        NextAction suggested = result.getNextAction();

        // 同一个话题问满轮数就强制换话题，不完全交给 LLM 判断。
        // ★ followUpCount 现在是「本话题已经答过几轮」，不是「连续 deepen 了几次」——
        // 旧写法只在 DEEPEN 时 +1，而答得中规中矩时 LLM 给的是 CONTINUE，
        // 计数器永远是 0，这个守卫一次都没触发过（实测 10 题全问在同一个话题上）。
        boolean forced = false;
        if (tracker.getFollowUpCount() >= props.getMaxFollowUp()) {
            forced = suggested != NextAction.SWITCH;
            if (forced) {
                log.info("话题「{}」已问满 {} 轮，把 LLM 给的 {} 改判为 SWITCH",
                        tracker.getCurrentTopic(), props.getMaxFollowUp(), suggested);
            }
            result.setNextAction(NextAction.SWITCH);
        }
        state.setEvalResult(result);

        String routing = InterviewRouting.decide(state);
        state.setLastRouting(routing);

        // ★ 顺序要紧：先把刚答完的这题标记成已覆盖，再挑下一个话题。
        // 反过来的话 suggestNextTopic() 会把当前话题又挑出来（它此刻还没被标记），
        // 于是落库的 next_topic 是旧话题、而 SwitchNode 稍后挑到的是新话题，两者对不上。
        tracker.markCovered(tracker.getCurrentTopic());

        // 只在 switch 路由下挑话题；其余路由置 null，避免残留上一轮的旧值。
        // 挑好之后放进 state，落库和 SwitchNode 共用这一个值，不再各算一遍。
        String nextTopic = InterviewRouting.SWITCH.equals(routing)
                ? tracker.suggestNextTopic() : null;
        state.setNextTopic(nextTopic);

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

        // 换话题就重新数，不换就累加。DEEPEN 和 CONTINUE 都算「还在这个话题上」
        tracker.setFollowUpCount(InterviewRouting.SWITCH.equals(routing)
                ? 0 : tracker.getFollowUpCount() + 1);

        // 一次评分打三行：分数和分支（含 LLM 建议 vs 实际走了哪条）、五维、评语。
        // 「LLM 建议」和「实际分支」要分开打——两者不一致时正是最能说明问题的一行。
        log.info("第 {} 题评分 | 话题={} 难度={} | {} ms | 总分={} | LLM 建议={} 实际分支={}{}",
                state.getQuestionIndex(), dialogue.getTopic(), dialogue.getDifficulty(),
                System.currentTimeMillis() - startedAt, result.getOverall(),
                suggested, routing, forced ? "（话题问满，已改判）" : "");
        log.info("第 {} 题五维 | {}", state.getQuestionIndex(), result.getDimensions());
        log.info("第 {} 题评语 | {}", state.getQuestionIndex(), result.getComment());

        // 答案已消费，清掉。这样下一轮回到 wait_answer 才会正确挂起
        state.setAnswer(null);
        return NodeResult.NEXT;
    }

    private EvalResult evaluate(LlmClient llm, PromptLoader prompts, InterviewProperties props,
                                InterviewState state, String question, String answer) {
        Map<String, String> vars = new HashMap<>();
        vars.put("position", orEmpty(state.getPosition()));
        vars.put("domain", orEmpty(state.getDomain()));
        vars.put("topic", orEmpty(state.getTopicTracker().getCurrentTopic()));
        vars.put("difficulty", state.getCurrentDifficulty().getLabel());
        vars.put("question", orEmpty(question));
        vars.put("answer", orEmpty(answer));
        // 告诉 LLM 这个话题还能问几轮。光靠我们自己改判也行，但它提前知道就能给出
        // 连贯的评语（「这个话题聊得比较透了」），而不是被强行掰到 switch 上
        vars.put("topicRounds", String.valueOf(state.getTopicTracker().getFollowUpCount() + 1));
        vars.put("maxFollowUp", String.valueOf(props.getMaxFollowUp()));

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