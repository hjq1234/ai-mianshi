package com.ke.nhservice.aimianshi.biz.interview;

import com.ke.nhservice.aimianshi.common.constant.NextAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次评分的完整结果。五个维度是复盘雷达图的数据源，一期就先采全。
 */
public class EvalResult {

    /** 五个维度的 key，顺序固定：决定了前端雷达图的轴顺序 */
    public static final List<String> DIMENSIONS =
            List.of("accuracy", "depth", "clarity", "practice", "problemSolving");

    public static final Map<String, String> DIMENSION_LABELS = Map.of(
            "accuracy", "准确性",
            "depth", "深度",
            "clarity", "表达",
            "practice", "实践",
            "problemSolving", "解题思路");

    /**
     * 允许留空的维度。
     *
     * practice 是唯一一个「题目本身决定了能不能打分」的维度：问的是纯概念（JVM 内存模型之类），
     * 候选人答得再好也谈不上「结合真实项目经验」。硬要求 LLM 给分的结果就是一堆 5-6 分的假数据，
     * 反而看不出候选人到底有没有实践。所以这里允许它是 null，复盘页显示成 `-`。
     */
    public static final List<String> NULLABLE_DIMENSIONS = List.of("practice");

    private double overall;
    private Map<String, Double> dimensions = new LinkedHashMap<>();
    private List<String> coveredTopics = List.of();
    private String comment = "";
    private NextAction nextAction = NextAction.CONTINUE;

    /** 评分服务不可用时的降级结果：不中断面试，按 CONTINUE 走 */
    public static EvalResult degraded(String reason) {
        EvalResult r = new EvalResult();
        r.setOverall(0);
        r.setComment("评分服务暂不可用（" + reason + "），本题不计入有效评分");
        r.setNextAction(NextAction.CONTINUE);
        Map<String, Double> dims = new LinkedHashMap<>();
        for (String key : DIMENSIONS) {
            // 可空维度给 null 而不是 0：0 看起来像「这块得零分」，null 才是「这题问不出来」
            dims.put(key, NULLABLE_DIMENSIONS.contains(key) ? null : 0.0);
        }
        r.setDimensions(dims);
        return r;
    }

    public double getOverall() { return overall; }

    public void setOverall(double overall) { this.overall = overall; }

    public Map<String, Double> getDimensions() { return dimensions; }

    public void setDimensions(Map<String, Double> dimensions) { this.dimensions = dimensions; }

    public List<String> getCoveredTopics() { return coveredTopics; }

    public void setCoveredTopics(List<String> coveredTopics) { this.coveredTopics = coveredTopics; }

    public String getComment() { return comment; }

    public void setComment(String comment) { this.comment = comment; }

    public NextAction getNextAction() { return nextAction; }

    public void setNextAction(NextAction nextAction) { this.nextAction = nextAction; }
}