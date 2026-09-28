package com.ke.nhservice.aimianshi.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.interview")
public class InterviewProperties {

    private int maxQuestions = 10;
    private int maxFollowUp = 3;
    private int maxSteps = 200;

    /** 话题池：技术方向 -> 话题列表。取自 application.yml 的 app.interview.topics */
    private Map<String, List<String>> topics = new LinkedHashMap<>();

    /** 取某个方向的话题池，取不到就退到「默认」 */
    public List<String> topicsOf(String domain) {
        if (domain != null) {
            List<String> hit = topics.get(domain.trim());
            if (hit != null && !hit.isEmpty()) {
                return hit;
            }
        }
        return topics.getOrDefault("默认", List.of("基础知识", "项目经验", "系统设计"));
    }

    public int getMaxQuestions() { return maxQuestions; }

    public void setMaxQuestions(int maxQuestions) { this.maxQuestions = maxQuestions; }

    public int getMaxFollowUp() { return maxFollowUp; }

    public void setMaxFollowUp(int maxFollowUp) { this.maxFollowUp = maxFollowUp; }

    public int getMaxSteps() { return maxSteps; }

    public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }

    public Map<String, List<String>> getTopics() { return topics; }

    public void setTopics(Map<String, List<String>> topics) { this.topics = topics; }
}