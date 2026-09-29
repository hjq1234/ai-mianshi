package com.ke.nhservice.aimianshi.biz.interview.node;

import com.ke.nhservice.aimianshi.biz.interview.Dialogue;
import com.ke.nhservice.aimianshi.biz.interview.HistoryItem;
import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.biz.interview.prompt.PromptLoader;
import com.ke.nhservice.aimianshi.common.config.InterviewProperties;
import com.ke.nhservice.aimianshi.graph.Node;
import com.ke.nhservice.aimianshi.graph.NodeContext;
import com.ke.nhservice.aimianshi.graph.NodeResult;
import com.ke.nhservice.aimianshi.wrapper.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 出题。拼 prompt → 调 LLM → 写 state.questionText。
 *
 * 失败策略：LLM 重试耗尽后异常直接往上抛，引擎转成 FAILED，游标停在 question。
 * 这不是「面试挂了」——state_json 已落库，用户刷新页面点继续就能重跑这一节点。
 * 只有 LLM 返回空内容这种「调用成功但结果不可用」才走 shouldStop 收尾。
 */
public class QuestionNode implements Node<InterviewState> {

    private static final Logger log = LoggerFactory.getLogger(QuestionNode.class);

    @Override
    public NodeResult execute(NodeContext ctx, InterviewState state) {
        LlmClient llm = ctx.get(LlmClient.class);
        PromptLoader prompts = ctx.get(PromptLoader.class);
        InterviewProperties props = ctx.get(InterviewProperties.class);

        boolean firstQuestion = state.getDialogues().isEmpty() && state.getQuestionIndex() <= 1;
        String template = firstQuestion ? "question_first" : "question_followup";

        Map<String, String> vars = new HashMap<>();
        vars.put("position", orEmpty(state.getPosition()));
        vars.put("company", orEmpty(state.getCompany()));
        vars.put("domain", orEmpty(state.getDomain()));
        vars.put("difficulty", state.getCurrentDifficulty().getLabel());
        vars.put("topic", orEmpty(state.getTopicTracker().getCurrentTopic()));
        vars.put("resume", orEmpty(state.getResumeSummary()));
        vars.put("history", renderHistory(state));
        vars.put("askedQuestions", renderAsked(state));
        vars.put("nextActionHint", orEmpty(state.getNextActionHint()));
        vars.put("practiceHint", practiceHint(prompts, props, state));
        vars.put("questionIndex", String.valueOf(state.getQuestionIndex()));
        vars.put("maxQuestions", String.valueOf(state.getMaxQuestions()));

        long startedAt = System.currentTimeMillis();
        String question = llm.chat(prompts.render(template, vars)).trim();
        long cost = System.currentTimeMillis() - startedAt;

        // 题目全文要打：出题是整条链上最贵的一步，事后想弄清「这题为什么这么问」只能靠它。
        // 但 prompt 本身不能打——简历摘要就拼在里面，那是候选人的隐私。
        log.info("第 {} 题出题完成 | 话题={} 难度={} | {} ms | {} 字符 | 题目：{}",
                state.getQuestionIndex(),
                state.getTopicTracker() == null ? "-" : state.getTopicTracker().getCurrentTopic(),
                state.getCurrentDifficulty().getLabel(),
                cost, question.length(), question);

        if (question.isBlank()) {
            state.setError("出题失败：模型返回了空内容");
            state.setShouldStop(true);
            return NodeResult.NEXT;
        }

        state.setQuestionText(question);
        // 上一题的东西全部清掉，保证 wait_answer 一定会挂起
        state.setAnswer(null);
        state.setEvalResult(null);
        state.setNextActionHint(null);
        return NodeResult.NEXT;
    }

    /** 滑动窗口里的最近 2 题。全带上会让 prompt 随轮次线性膨胀，而 token 是按量计费的 */
    private String renderHistory(InterviewState state) {
        if (state.getRecentHistory().isEmpty()) {
            return "（这是第一个问题，暂无历史）";
        }
        StringBuilder sb = new StringBuilder();
        for (HistoryItem item : state.getRecentHistory()) {
            sb.append("问：").append(item.getQuestion()).append('\n');
            sb.append("答：").append(item.getAnswer()).append('\n');
            sb.append("得分：").append(item.getScore()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 已经问过的所有题目，只给题目不给答案。
     *
     * 原来只靠「最近 2 题」的滑动窗口去重，视野太窄：实测第 5 题和第 8 题都在问
     * final 域与 this 逸出。这里把全部题面列出来（10 题也就几百字），让 LLM 自己看清哪些问过了。
     * 答案不带——去重只需要题面，带上答案 prompt 会随轮次线性膨胀。
     */
    private String renderAsked(InterviewState state) {
        if (state.getDialogues().isEmpty()) {
            return "（还没有问过任何问题）";
        }
        StringBuilder sb = new StringBuilder();
        for (Dialogue item : state.getDialogues()) {
            sb.append(item.getSeq()).append(". ").append(item.getQuestion()).append('\n');
        }
        return sb.toString().trim();
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 话题是「项目经历」时附上专门的要求，其余话题给空串。
     *
     * 空串不是偷懒：PromptLoader 对「没传的占位符」会原样留着 {practiceHint}，
     * 所以这里必须无条件给出一个值（哪怕是空的），不能只在命中时才 put。
     */
    private String practiceHint(PromptLoader prompts, InterviewProperties props, InterviewState state) {
        String topic = state.getTopicTracker() == null
                ? null : state.getTopicTracker().getCurrentTopic();
        return props.getPracticeTopic() != null && props.getPracticeTopic().equals(topic)
                ? prompts.load("hint_practice") : "";
    }
}