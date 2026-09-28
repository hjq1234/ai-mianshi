package com.ke.nhservice.aimianshi.biz.interview.node;

import com.ke.nhservice.aimianshi.biz.interview.InterviewDao;
import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.biz.interview.RecordRow;
import com.ke.nhservice.aimianshi.biz.interview.TopicTracker;
import com.ke.nhservice.aimianshi.common.config.InterviewProperties;
import com.ke.nhservice.aimianshi.common.constant.Difficulty;
import com.ke.nhservice.aimianshi.graph.Node;
import com.ke.nhservice.aimianshi.graph.NodeContext;
import com.ke.nhservice.aimianshi.graph.NodeResult;

import java.util.List;

/**
 * 初始化：题号归 1，把会话配置和话题池灌进 state。
 * 只跑一次——游标离开 start 之后就不会再回来。
 */
public class StartNode implements Node<InterviewState> {

    @Override
    public NodeResult execute(NodeContext ctx, InterviewState state) {
        InterviewProperties props = ctx.get(InterviewProperties.class);
        InterviewDao dao = ctx.get(InterviewDao.class);

        RecordRow row = dao.findRecord(state.getRecordId())
                .orElseThrow(() -> new IllegalStateException(
                        "面试记录不存在: " + state.getRecordId()));

        state.setQuestionIndex(1);
        state.setMaxQuestions(props.getMaxQuestions());
        state.setPosition(row.position());
        state.setCompany(row.company());
        state.setDomain(row.domain());
        state.setCurrentDifficulty(Difficulty.fromLabel(row.difficulty()));
        state.setResumeSummary(row.resumeSummary() == null ? "" : row.resumeSummary());
        state.setShouldStop(false);
        state.setError(null);

        List<String> topics = props.topicsOf(row.domain());
        TopicTracker tracker = new TopicTracker();
        tracker.setAllTopics(topics);
        // 第一题就用话题池里的第一个，currentTopic 不能为 null
        tracker.setCurrentTopic(tracker.suggestNextTopic());
        state.setTopicTracker(tracker);

        return NodeResult.NEXT;
    }
}