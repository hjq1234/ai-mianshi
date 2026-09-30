package com.ke.nhservice.aimianshi.biz.resume;

import com.ke.nhservice.aimianshi.biz.interview.Dialogue;
import com.ke.nhservice.aimianshi.biz.interview.EvalResult;
import com.ke.nhservice.aimianshi.biz.interview.InterviewStats;
import com.ke.nhservice.aimianshi.biz.interview.RecordRow;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewTarget;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把「目标岗位」和「挑中的几场面试」拼成提示词里的两段文字。
 *
 * 单独一个纯函数类：interview 那边只提供数据（RecordRow + Dialogue），不认识「简历改稿」这回事；
 * 素材怎么摆进 prompt 是 resume 侧的排版问题。
 *
 * 无状态、不碰库、不碰 LLM，所以边界（一场没选、选了但一题没答、practice 是 null）离线就能想清楚。
 */
public final class ResumeReviewMaterial {

    /** 序号用 ①②③… 好看；超过十个退回 "11)" 这种写法，不为边角情况堆一整个数组 */
    private static final String CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩";

    /** 一场面试记录都没选 */
    static final String NO_INTERVIEW = "（未提供）";

    /** 选了，但一场都没答过题（图跑挂过的那种场次） */
    static final String NO_ANSWERED = "（还没有已完成的面试）";

    private ResumeReviewMaterial() {
    }

    /** 目标岗位。岗位名必填（service 那边已经校验过），JD 可空 */
    public static String targets(List<ResumeReviewTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return NO_INTERVIEW;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < targets.size(); i++) {
            ResumeReviewTarget t = targets.get(i);
            String jd = t.jd() == null ? "" : t.jd().strip();
            sb.append(index(i + 1)).append(' ').append(t.title()).append('\n');
            sb.append("   JD：")
              .append(jd.isEmpty() ? "（未提供，按该岗位通用标准）" : jd)
              .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 面试表现素材。
     *
     * ★ 一题没答过的场次要**跳过**：图跑挂过的场次也会是 finished 但没有任何对话，
     *   摆进来对模型没信息量，「这题 0 分」还会误导它（那是引擎挂了，不是候选人答砸了）。
     *   全部场次都跳过了才说一句「还没有已完成的面试」。
     */
    public static String interviews(List<RecordRow> records,
                                    Map<Long, List<Dialogue>> dialoguesByRecord) {
        if (records == null || records.isEmpty()) {
            return NO_INTERVIEW;
        }
        Map<Long, List<Dialogue>> byRecord =
                dialoguesByRecord == null ? Map.of() : dialoguesByRecord;

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (RecordRow row : records) {
            List<Dialogue> dialogues = byRecord.getOrDefault(row.id(), List.of());
            if (dialogues.isEmpty()) {
                continue;
            }
            shown++;
            sb.append("第 ").append(shown).append(" 场 · ")
              .append(text(row.position(), "未填岗位")).append(" / ")
              .append(text(row.domain(), "未填方向")).append(" · ")
              .append(text(row.difficulty(), "未填难度")).append(" · 总分 ")
              .append(score(row.totalScore())).append(" · 已答 ")
              .append(dialogues.size()).append(" 题\n");
            for (Dialogue d : dialogues) {
                sb.append("  第").append(d.getSeq()).append("题 ")
                  .append(text(d.getTopic(), "综合")).append(' ')
                  .append(score(d.getScore())).append('\n');
            }
            sb.append("  ").append(dimensions(dialogues)).append('\n');
        }
        return shown == 0 ? NO_ANSWERED : sb.toString().stripTrailing();
    }

    /**
     * 这一场的五维均分。
     *
     * ★ 算法**不在这里重写一遍**：InterviewStats 一个人管着「null 维度既不进分子也不进分母」，
     *   这里只是把它算出来的结果摆成一行字。
     *   finishedCount 传 1 是占位（那个字段是给「历史几场」用的），
     *   和 InterviewController.detail 里的用法一致。
     *
     * 维度名用 EvalResult.DIMENSION_LABELS，**不要另起一套**——
     * 同一个维度在提示词里出现两个名字，模型会当成两件事。
     */
    private static String dimensions(List<Dialogue> dialogues) {
        Map<String, Double> averages = InterviewStats.of(1, dialogues).dimensions();
        if (averages.isEmpty()) {
            return "五维均分：（本场没有可统计的维度）";
        }
        StringBuilder sb = new StringBuilder("五维均分：");
        boolean first = true;
        for (Map.Entry<String, Double> entry : averages.entrySet()) {
            if (!first) {
                sb.append(" / ");
            }
            first = false;
            sb.append(EvalResult.DIMENSION_LABELS.getOrDefault(entry.getKey(), entry.getKey()))
              .append(' ').append(score(entry.getValue()));
        }
        return sb.toString();
    }

    private static String index(int n) {
        return n <= CIRCLED.length() ? String.valueOf(CIRCLED.charAt(n - 1)) : n + ")";
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /** 分数统一一位小数；null（没打分）显示成 -，**不能显示成 0**（那是「打了 0 分」） */
    private static String score(Double value) {
        // 用 Locale.ROOT 而不是默认 locale：这是 prompt 里的数字，
        // 落到某些 locale 会变成 "7,2" 这种小数点
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f", value);
    }
}