package com.ke.nhservice.aimianshi.biz.interview;

/**
 * 滑动窗口里的一题。只保留最近 2 题——
 * token 按量计费，历史全带上会让 prompt 随轮次线性膨胀。
 */
public class HistoryItem {

    private String question;
    private String answer;
    private Double score;

    public HistoryItem() {
    }

    public HistoryItem(String question, String answer, Double score) {
        this.question = question;
        this.answer = answer;
        this.score = score;
    }

    public String getQuestion() { return question; }

    public void setQuestion(String question) { this.question = question; }

    public String getAnswer() { return answer; }

    public void setAnswer(String answer) { this.answer = answer; }

    public Double getScore() { return score; }

    public void setScore(Double score) { this.score = score; }
}