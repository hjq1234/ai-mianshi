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
            dims.put(key, 0.0);
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