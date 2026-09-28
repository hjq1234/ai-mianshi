package com.ke.nhservice.aimianshi.biz.interview.node;

import com.ke.nhservice.aimianshi.biz.interview.Dialogue;
import com.ke.nhservice.aimianshi.biz.interview.InterviewDao;
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
 * 收尾：生成综合报告，把记录标成 finished。
 *
 * 这个节点是图的终点，执行完引擎返回 FINISHED。
 * 报告生成失败也要落库一个降级版本——不然用户会看到一场「结束了但没有报告」的面试。
 */
public class EndNode implements Node<InterviewState> {

    private static final Logger log = LoggerFactory.getLogger(EndNode.class);

    @Override
    public NodeResult execute(NodeContext ctx, InterviewState state) {
        InterviewDao dao = ctx.get(InterviewDao.class);
        PromptLoader prompts = ctx.get(PromptLoader.class);
        LlmClient llm = ctx.get(LlmClient.class);

        double total = state.getScoreHistory().total();
        double average = state.getScoreHistory().average();
        int count = state.getScoreHistory().getScores().size();

        Map<String, String> vars = new HashMap<>();
        vars.put("position", orEmpty(state.getPosition()));
        vars.put("domain", orEmpty(state.getDomain()));
        vars.put("count", String.valueOf(count));
        vars.put("total", String.format("%.1f", total));
        vars.put("average", String.format("%.1f", average));
        vars.put("detail", renderDetail(state));
        vars.put("error", state.getError() == null || state.getError().isBlank()
                ? "" : "\n注意：本次面试因故提前结束，原因：" + state.getError() + "\n");

        String report;
        try {
            report = llm.chat(prompts.render("report", vars));
        } catch (Exception e) {
            log.warn("生成综合报告失败，落库降级版本: {}", e.getMessage());
            report = "## 综合报告生成失败\n\n原因：" + e.getMessage()
                    + "\n\n以下是本次面试的原始记录：\n\n" + renderDetail(state);
        }

        state.setReport(report);
        state.setTotalScore(average);
        dao.finishRecord(state.getRecordId(), average, report);

        log.info("面试 {} 结束：{} 题，平均分 {}", state.getRecordId(), count,
                String.format("%.1f", average));
        return NodeResult.NEXT;
    }

    private String renderDetail(InterviewState state) {
        if (state.getDialogues().isEmpty()) {
            return "（本次面试没有产生有效问答记录）";
        }
        StringBuilder sb = new StringBuilder();
        for (Dialogue d : state.getDialogues()) {
            sb.append("第 ").append(d.getSeq()).append(" 题");
            if (d.getTopic() != null) {
                sb.append("（话题：").append(d.getTopic())
                        .append("，难度：").append(d.getDifficulty()).append("）");
            }
            sb.append('\n');
            sb.append("问：").append(d.getQuestion()).append('\n');
            sb.append("答：").append(d.getAnswer()).append('\n');
            sb.append("得分：").append(d.getScore()).append('\n');
            if (d.getComment() != null && !d.getComment().isBlank()) {
                sb.append("评语：").append(d.getComment()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }
}