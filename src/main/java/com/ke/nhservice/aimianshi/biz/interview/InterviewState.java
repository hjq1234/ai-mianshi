package com.ke.nhservice.aimianshi.biz.interview;

import com.ke.nhservice.aimianshi.common.constant.Difficulty;

import java.util.ArrayList;
import java.util.List;

/**
 * 面试的业务状态。整个对象序列化成 JSON 存进 t_interview_record.state_json。
 *
 * 这个类必须能被 Jackson 无参构造 + getter/setter 完整还原，
 * 因为它就是「断点续传」的全部依据。
 */
public class InterviewState {

    // ── 会话标识 ──
    private Long recordId;
    private String sessionId;

    // ── 会话配置（StartNode 从记录表带进来，供出题提示词使用）──
    private String position;
    private String company;
    private String domain;
    private String resumeSummary;

    // ── 当前轮次 ──
    /** 从 1 开始 */
    private int questionIndex;
    /** 本题难度，初始 = 会话难度，被 deepen/lower 调整 */
    private Difficulty currentDifficulty = Difficulty.MEDIUM;
    private String questionText;
    /** 挂起后由下一次 HTTP 请求塞入；被 evaluate 消费后清空 */
    private String answer;
    private EvalResult evalResult;

    // ── 分支节点写入的「下一题方向」──
    private String nextActionHint;

    /**
     * 本题路由为 switch 时，接下来要聊的话题（由 EvaluateNode 挑好）。
     * SwitchNode 只负责把它落到 currentTopic 上，不重新挑一次——
     * 否则「落库的 next_topic」和「实际聊的话题」会各算各的，迟早对不上。
     * 非 switch 路由时显式置 null，避免残留上一轮的旧值。
     */
    private String nextTopic;

    // ── 累积状态（跨轮次）──
    private List<Dialogue> dialogues = new ArrayList<>();
    private ScoreHistory scoreHistory = new ScoreHistory();
    private TopicTracker topicTracker = new TopicTracker();
    /** 滑动窗口，只保留最近 2 题 */
    private List<HistoryItem> recentHistory = new ArrayList<>();

    // ── 控制 ──
    private boolean shouldStop;
    private int maxQuestions = 10;
    private String error;

    // ── 收尾产物（EndNode 写入）──
    private String report;
    private Double totalScore;
    /** 本题实际走的分支（deepen/continue/lower/switch/end），供接口返回与复盘展示 */
    private String lastRouting;

    public void pushHistory(HistoryItem item, int keep) {
        recentHistory.add(item);
        while (recentHistory.size() > keep) {
            recentHistory.remove(0);
        }
    }

    public Long getRecordId() { return recordId; }

    public void setRecordId(Long recordId) { this.recordId = recordId; }

    public String getSessionId() { return sessionId; }

    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getPosition() { return position; }

    public void setPosition(String position) { this.position = position; }

    public String getCompany() { return company; }

    public void setCompany(String company) { this.company = company; }

    public String getDomain() { return domain; }

    public void setDomain(String domain) { this.domain = domain; }

    public String getResumeSummary() { return resumeSummary; }

    public void setResumeSummary(String resumeSummary) { this.resumeSummary = resumeSummary; }

    public int getQuestionIndex() { return questionIndex; }

    public void setQuestionIndex(int questionIndex) { this.questionIndex = questionIndex; }

    public Difficulty getCurrentDifficulty() { return currentDifficulty; }

    public void setCurrentDifficulty(Difficulty currentDifficulty) {
        this.currentDifficulty = currentDifficulty;
    }

    public String getQuestionText() { return questionText; }

    public void setQuestionText(String questionText) { this.questionText = questionText; }

    public String getAnswer() { return answer; }

    public void setAnswer(String answer) { this.answer = answer; }

    public EvalResult getEvalResult() { return evalResult; }

    public void setEvalResult(EvalResult evalResult) { this.evalResult = evalResult; }

    public String getNextActionHint() { return nextActionHint; }

    public void setNextActionHint(String nextActionHint) { this.nextActionHint = nextActionHint; }

    public String getNextTopic() { return nextTopic; }

    public void setNextTopic(String nextTopic) { this.nextTopic = nextTopic; }

    public List<Dialogue> getDialogues() { return dialogues; }

    public void setDialogues(List<Dialogue> dialogues) { this.dialogues = dialogues; }

    public ScoreHistory getScoreHistory() { return scoreHistory; }

    public void setScoreHistory(ScoreHistory scoreHistory) { this.scoreHistory = scoreHistory; }

    public TopicTracker getTopicTracker() { return topicTracker; }

    public void setTopicTracker(TopicTracker topicTracker) { this.topicTracker = topicTracker; }

    public List<HistoryItem> getRecentHistory() { return recentHistory; }

    public void setRecentHistory(List<HistoryItem> recentHistory) { this.recentHistory = recentHistory; }

    public boolean isShouldStop() { return shouldStop; }

    public void setShouldStop(boolean shouldStop) { this.shouldStop = shouldStop; }

    public int getMaxQuestions() { return maxQuestions; }

    public void setMaxQuestions(int maxQuestions) { this.maxQuestions = maxQuestions; }

    public String getError() { return error; }

    public void setError(String error) { this.error = error; }

    public String getReport() { return report; }

    public void setReport(String report) { this.report = report; }

    public Double getTotalScore() { return totalScore; }

    public void setTotalScore(Double totalScore) { this.totalScore = totalScore; }

    public String getLastRouting() { return lastRouting; }

    public void setLastRouting(String lastRouting) { this.lastRouting = lastRouting; }
}