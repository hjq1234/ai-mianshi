package com.ke.nhservice.aimianshi.biz.interview.node;

import com.ke.nhservice.aimianshi.biz.interview.HistoryItem;
import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.biz.interview.prompt.PromptLoader;
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
        vars.put("nextActionHint", orEmpty(state.getNextActionHint()));
        vars.put("questionIndex", String.valueOf(state.getQuestionIndex()));
        vars.put("maxQuestions", String.valueOf(state.getMaxQuestions()));

        String question = llm.chat(prompts.render(template, vars)).trim();
        log.debug("第 {} 题生成完成，{} 字符", state.getQuestionIndex(), question.length());

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

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }
}