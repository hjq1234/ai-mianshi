package com.ke.nhservice.aimianshi.biz.interview;

import com.ke.nhservice.aimianshi.common.dto.InterviewStatsVO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一堆逐题记录压成一份五维均分。纯计算，不碰库、不碰 LLM。
 *
 * 单独一个类的理由：「null 维度怎么算」这条规则只该有一处实现。
 * practice 在纯概念题上是 null（不是 0），如果按 0 参与平均，
 * 历史均分会被系统性拉低——一个只问八股的场次能把 7 分拉成 5 分。
 * 所以规则是：**null 既不进分子也不进分母**。
 *
 * 无状态、无依赖，所以离线验边界（空场次、全 null、只有一场）很容易。
 */
public final class InterviewStats {

    private InterviewStats() {
    }

    /**
     * @param finishedCount 已完成的场次，只用来告诉前端「这个均分是几场算出来的」
     * @param dialogues     这些场次的全部逐题记录。可以为空
     */
    public static InterviewStatsVO of(int finishedCount, List<Dialogue> dialogues) {
        Map<String, Double> sums = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        double scoreSum = 0;
        int scoreCount = 0;

        for (Dialogue d : dialogues == null ? List.<Dialogue>of() : dialogues) {
            if (d.getScore() != null) {
                scoreSum += d.getScore();
                scoreCount++;
            }
            Map<String, Double> dims = d.getDimensions();
            if (dims == null) {
                continue;
            }
            for (Map.Entry<String, Double> entry : dims.entrySet()) {
                Double value = entry.getValue();
                if (value == null) {
                    // ★ 跳过，不是当 0。见类注释
                    continue;
                }
                sums.merge(entry.getKey(), value, Double::sum);
                counts.merge(entry.getKey(), 1, Integer::sum);
            }
        }

        // 按 EvalResult.DIMENSIONS 的固定顺序输出。雷达图的五个轴是按序取的，
        // 顺序乱了图也就乱了；而且 HashMap 的顺序本来就不保证
        Map<String, Double> dimensions = new LinkedHashMap<>();
        for (String key : EvalResult.DIMENSIONS) {
            Integer n = counts.get(key);
            if (n != null && n > 0) {
                dimensions.put(key, sums.get(key) / n);
            }
        }

        return new InterviewStatsVO(
                finishedCount,
                dimensions,
                scoreCount == 0 ? null : scoreSum / scoreCount);
    }
}