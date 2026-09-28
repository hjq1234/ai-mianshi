package com.ke.nhservice.aimianshi.biz.interview;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一道题的完整记录。同时是 t_interview_dialogue 一行的 Java 形态。
 */
public class Dialogue {

    private int seq;
    private String topic;
    private String difficulty;      // 中文标签：简单/中等/困难
    private String question;
    private String answer;
    private Double score;
    private Map<String, Double> dimensions = new LinkedHashMap<>();
    private String comment;
    private String nextAction;      // deepen|continue|lower|switch|end
    private String nextTopic;       // 走 switch 时换到的话题

    public int getSeq() { return seq; }

    public void setSeq(int seq) { this.seq = seq; }

    public String getTopic() { return topic; }

    public void setTopic(String topic) { this.topic = topic; }

    public String getDifficulty() { return difficulty; }

    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public String getQuestion() { return question; }

    public void setQuestion(String question) { this.question = question; }

    public String getAnswer() { return answer; }

    public void setAnswer(String answer) { this.answer = answer; }

    public Double getScore() { return score; }

    public void setScore(Double score) { this.score = score; }

    public Map<String, Double> getDimensions() { return dimensions; }

    public void setDimensions(Map<String, Double> dimensions) { this.dimensions = dimensions; }

    public String getComment() { return comment; }

    public void setComment(String comment) { this.comment = comment; }

    public String getNextAction() { return nextAction; }

    public void setNextAction(String nextAction) { this.nextAction = nextAction; }

    public String getNextTopic() { return nextTopic; }

    public void setNextTopic(String nextTopic) { this.nextTopic = nextTopic; }
}