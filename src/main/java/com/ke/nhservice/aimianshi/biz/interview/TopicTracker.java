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

    /** 当前话题连续追问了几次。达到上限就强制换话题（见 EvaluateNode） */
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

    public void setCoveredTopics(List<String> coveredTopics) {
        this.coveredTopics = coveredTopics == null ? new ArrayList<>() : coveredTopics;
    }

    public String getCurrentTopic() { return currentTopic; }

    public void setCurrentTopic(String currentTopic) { this.currentTopic = currentTopic; }

    public int getFollowUpCount() { return followUpCount; }

    public void setFollowUpCount(int followUpCount) { this.followUpCount = followUpCount; }
}