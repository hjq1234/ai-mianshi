package com.ke.nhservice.aimianshi.biz.interview;

import java.util.ArrayList;
import java.util.List;

public class ScoreHistory {

    private List<Double> scores = new ArrayList<>();

    public void add(double score) {
        scores.add(score);
    }

    public List<Double> getScores() { return scores; }

    public void setScores(List<Double> scores) { this.scores = scores; }

    public double total() {
        double sum = 0;
        for (Double s : scores) {
            sum += s == null ? 0 : s;
        }
        return sum;
    }

    public double average() {
        return scores.isEmpty() ? 0 : total() / scores.size();
    }
}