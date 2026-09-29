package com.ke.nhservice.aimianshi.biz.interview;

import java.util.ArrayList;
import java.util.List;

/**
 * 话题追踪。累积状态随 InterviewState 一起序列化进 state_json，所以跨请求保持。
 *
 * 话题池来自配置（app.interview.topics），不让 LLM 现场生成——
 * 话题池决定了整场面试的覆盖面，需要稳定可控、可人工调整，还省掉一次 LLM 调用。
 */
public class TopicTracker {

    private List<String> allTopics = new ArrayList<>();
    private List<String> coveredTopics = new ArrayList<>();
    private String currentTopic;

    /**
     * 当前话题已经答过几轮。达到上限就强制换话题（见 EvaluateNode）。
     *
     * 注意是「本话题答过几轮」而不是「连续追问了几次」：旧实现只在 LLM 给 DEEPEN 时 +1，
     * 而答得中规中矩时 LLM 给的是 CONTINUE，计数器永远是 0，上限一次都没生效过
     * （实测一整场 10 题全问在同一个话题上）。
     */
    private int followUpCount;

    /** 挑一个还没聊过的话题；全聊完了就回到第一个，允许循环 */
    public String suggestNextTopic() {
        if (allTopics.isEmpty()) {
            return currentTopic;
        }
        for (String topic : allTopics) {
            if (!coveredTopics.contains(topic)) {
                return topic;
            }
        }
        return allTopics.get(0);
    }

    public void markCovered(String topic) {
        if (topic != null && !coveredTopics.contains(topic)) {
            coveredTopics.add(topic);
        }
    }

    public List<String> getAllTopics() { return allTopics; }

    public void setAllTopics(List<String> allTopics) {
        this.allTopics = allTopics == null ? new ArrayList<>() : new ArrayList<>(allTopics);
    }

    public List<String> getCoveredTopics() { return coveredTopics; }

    /** 和 setAllTopics 一样拷贝一份：直接把传进来的 List 存下来，
     * 遇到 List.of() 这类不可变实现时 markCovered 会抛 UnsupportedOperationException */
    public void setCoveredTopics(List<String> coveredTopics) {
        this.coveredTopics = coveredTopics == null ? new ArrayList<>() : new ArrayList<>(coveredTopics);
    }

    public String getCurrentTopic() { return currentTopic; }

    public void setCurrentTopic(String currentTopic) { this.currentTopic = currentTopic; }

    public int getFollowUpCount() { return followUpCount; }

    public void setFollowUpCount(int followUpCount) { this.followUpCount = followUpCount; }
}