package com.ke.nhservice.aimianshi.biz.interview;

import com.ke.nhservice.aimianshi.biz.interview.flow.InterviewGraphFactory;
import com.ke.nhservice.aimianshi.biz.interview.prompt.PromptLoader;
import com.ke.nhservice.aimianshi.biz.interview.trace.TraceRecorder;
import com.ke.nhservice.aimianshi.common.config.InterviewProperties;
import com.ke.nhservice.aimianshi.common.constant.Difficulty;
import com.ke.nhservice.aimianshi.common.constant.RecordStatus;
import com.ke.nhservice.aimianshi.common.dto.InterviewStatsVO;
import com.ke.nhservice.aimianshi.common.exception.BizException;
import com.ke.nhservice.aimianshi.common.util.JsonUtil;
import com.ke.nhservice.aimianshi.graph.CompiledGraph;
import com.ke.nhservice.aimianshi.graph.Execution;
import com.ke.nhservice.aimianshi.graph.NodeContext;
import com.ke.nhservice.aimianshi.graph.RunResult;
import com.ke.nhservice.aimianshi.wrapper.llm.LlmClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 面试引擎：把「HTTP 请求」翻译成「跑图 + 落库」。
 *
 * 四个入口（start / answer / finish / resume）做的是同一件事的不同变体：
 * 载入现场 → 改一下 state 或 cursor → 跑图 → 存回现场。
 *
 * 「继续面试」不需要专门的恢复逻辑——现场全在 state_json + cursor 里，
 * 读一条记录就能接着跑。
 */
@Service
public class InterviewEngine {

    private static final Logger log = LoggerFactory.getLogger(InterviewEngine.class);

    private final InterviewDao dao;
    private final InterviewGraphFactory graphFactory;
    private final TraceRecorder traceRecorder;
    private final LlmClient llmClient;
    private final PromptLoader promptLoader;
    private final InterviewProperties props;
    private final NodeContext nodeContext = new NodeContext();

    /** 图不可变、节点无状态，编译一次全局复用 */
    private CompiledGraph<InterviewState> graph;

    public InterviewEngine(InterviewDao dao,
                           InterviewGraphFactory graphFactory,
                           TraceRecorder traceRecorder,
                           LlmClient llmClient,
                           PromptLoader promptLoader,
                           InterviewProperties props) {
        this.dao = dao;
        this.graphFactory = graphFactory;
        this.traceRecorder = traceRecorder;
        this.llmClient = llmClient;
        this.promptLoader = promptLoader;
        this.props = props;
    }

    @PostConstruct
    void init() {
        nodeContext.put(LlmClient.class, llmClient)
                .put(InterviewDao.class, dao)
                .put(PromptLoader.class, promptLoader)
                .put(InterviewProperties.class, props);
        this.graph = graphFactory.build(nodeContext, traceRecorder);
        log.info("面试图已编译：起始节点 {}，结束节点 {}，最大步数 {}",
                graph.getStart(), graph.getEnd(), props.getMaxSteps());
    }

    // ────────────────────────── 四个入口 ──────────────────────────

    /** 新建一场面试，跑到第一个问题挂起为止 */
    public RunResult<InterviewState> start(Long userId, Long resumeId, String position,
                                           String company, String domain, String difficulty) {
        long recordId = dao.insertRecord(userId, resumeId, position, company, domain, difficulty);

        InterviewState state = new InterviewState();
        state.setRecordId(recordId);
        state.setSessionId(UUID.randomUUID().toString());
        state.setMaxQuestions(props.getMaxQuestions());
        state.setCurrentDifficulty(Difficulty.fromLabel(difficulty));

        log.info("开始面试 | 面试={} 用户={} | 岗位={} 公司={} 方向={} 难度={} 简历={}",
                recordId, userId, position, company, domain, difficulty,
                resumeId == null ? "未使用" : resumeId);
        // cursor 传 null，引擎会从 start 节点开始
        return run(state, null);
    }

    /** 提交答案，跑到下一个问题挂起为止 */
    public RunResult<InterviewState> submitAnswer(Long userId, Long recordId, String answer) {
        Loaded loaded = loadOwned(userId, recordId);
        if (loaded.isFinished()) {
            throw new BizException("这场面试已经结束了");
        }
        if (answer == null || answer.isBlank()) {
            throw new BizException("答案不能为空");
        }
        loaded.state().setAnswer(answer.trim());
        loaded.state().setShouldStop(false);
        // 回答正文不打：候选人的作答内容没必要进日志，字符数够定位「是不是空提交」
        log.info("提交回答 | 面试={} 第 {} 题 | 回答 {} 字符",
                recordId, loaded.state().getQuestionIndex(), answer.trim().length());
        return run(loaded.state(), loaded.cursor());
    }

    /**
     * 用户主动结束。直接把游标跳到结束节点——
     * 这是「Execution.cursor 存在库中且外部可改」的直接应用，
     * 不需要为「中途结束」在 NodeResult 里加第三种指令。
     */
    public RunResult<InterviewState> finish(Long userId, Long recordId) {
        Loaded loaded = loadOwned(userId, recordId);
        if (loaded.isFinished()) {
            return RunResult.finished(InterviewGraphFactory.NODE_END, loaded.state());
        }
        loaded.state().setShouldStop(true);
        log.info("主动结束面试 | 面试={} 已答 {} 题",
                recordId, loaded.state().getDialogues().size());
        return run(loaded.state(), InterviewGraphFactory.NODE_END);
    }

    /** 从上次的游标继续跑。刷新页面、上次 LLM 失败、关掉浏览器再回来，都走这里 */
    public RunResult<InterviewState> resume(Long userId, Long recordId) {
        Loaded loaded = loadOwned(userId, recordId);
        if (loaded.isFinished()) {
            return RunResult.finished(InterviewGraphFactory.NODE_END, loaded.state());
        }
        log.info("继续面试 | 面试={} 游标={}", recordId, loaded.cursor());
        return run(loaded.state(), loaded.cursor());
    }

    /** 只读当前状态，不推进图 */
    public InterviewState loadState(Long userId, Long recordId) {
        return loadOwned(userId, recordId).state();
    }

    // ────────────────────────── 只读查询 ──────────────────────────

    /** 面试记录列表（分页） */
    public List<RecordRow> list(Long userId, int page, int size) {
        int safeSize = Math.max(1, Math.min(100, size));
        int offset = Math.max(0, (Math.max(1, page) - 1) * safeSize);
        return dao.listByUser(userId, safeSize, offset);
    }

    public int count(Long userId) {
        return dao.countByUser(userId);
    }

    /** 每场面试答了几题，列表页一次查完 */
    public Map<Long, Integer> dialogueCounts(List<Long> recordIds) {
        return dao.countDialoguesByRecord(recordIds);
    }

    /** 复盘详情。归属校验统一走 loadOwned，不在这里重复写一遍 */
    public RecordRow requireRecord(Long userId, Long recordId) {
        return loadOwned(userId, recordId).row();
    }

    public List<Dialogue> dialogues(Long recordId) {
        return dao.listDialogues(recordId);
    }

    /**
     * 软删一场面试记录。
     *
     * 复用 requireRecord 而不是自己写归属校验：它里面就是 loadOwned，
     * 「不存在」和「不是你的」都统一抛 notFound（面试记录不存在），
     * 所以已删的记录在这里自动也是 404——对同一条再删一次会 404，是正确行为不是 bug。
     *
     * 不判断状态：进行中的也能删（半途放弃的正是最该清理的那类）。
     */
    public void delete(Long userId, Long recordId) {
        requireRecord(userId, recordId);
        dao.softDelete(recordId);
        log.info("删除面试记录 | 面试={} 用户={}", recordId, userId);
    }

    public List<TraceRow> traces(Long recordId) {
        return dao.listTraces(recordId);
    }

    /** 历史均分，给复盘页雷达图做对比。一场都没答过题时返回 finishedCount=0 */
    public InterviewStatsVO stats(Long userId) {
        List<Long> finishedIds = dao.listFinishedIdsWithAnswers(userId);
        if (finishedIds.isEmpty()) {
            return InterviewStats.of(0, List.of());
        }
        return InterviewStats.of(finishedIds.size(), dao.listDialoguesByRecords(finishedIds));
    }

    // ────────────────────────── 内部 ──────────────────────────

    private RunResult<InterviewState> run(InterviewState state, String cursor) {
        Execution<InterviewState> execution = new Execution<>(state, cursor);
        long startedAt = System.currentTimeMillis();
        RunResult<InterviewState> result = graph.run(execution);
        // 一个请求一行汇总：这次跑到哪停了、游标落在哪、一共花了多久。
        // 配合 RequestLogFilter 的 HTTP 行，能立刻分出「时间花在 LLM 上」还是「花在别处」
        log.info("图执行结束 | 面试={} 状态={} 停在={} 游标={} | {} ms",
                state.getRecordId(), result.status(), result.stoppedAt(),
                execution.getCursor(), System.currentTimeMillis() - startedAt);

        // 无论成功、挂起还是失败，现场都必须落库。
        // 这一步是「已答的题不会丢」的全部保障。
        persist(state, execution.getCursor(), result);
        return result;
    }

    private void persist(InterviewState state, String cursor, RunResult<InterviewState> result) {
        dao.saveState(state.getRecordId(), JsonUtil.toJson(state), cursor);
        switch (result.status()) {
            case FAILED -> log.error("面试 {} 在节点 {} 执行失败，游标已保留，可重新唤起继续",
                    state.getRecordId(), result.stoppedAt(), result.error());
            case STEP_LIMIT -> log.error("面试 {} 超过最大步数被中断，停在节点 {}，可能是图配置有环",
                    state.getRecordId(), result.stoppedAt());
            default -> {
            }
        }
    }

    /**
     * 载入现场。注意 cursor 为 null 是合法情况——
     * 那表示图停在起始节点之前，引擎会自动从 start 开始。
     */
    private Loaded loadOwned(Long userId, Long recordId) {
        RecordRow row = dao.findRecord(recordId)
                .orElseThrow(() -> BizException.notFound("面试记录不存在"));

        if (!row.userId().equals(userId)) {
            // 不区分「不存在」和「不是你的」，避免探测别人的记录 id
            throw BizException.notFound("面试记录不存在");
        }

        InterviewState state;
        if (row.stateJson() == null || row.stateJson().isBlank()) {
            state = new InterviewState();
            state.setRecordId(recordId);
            state.setMaxQuestions(props.getMaxQuestions());
        } else {
            state = JsonUtil.fromJson(row.stateJson(), InterviewState.class);
            // 快照里的 recordId 理论上一定有，兜一下防止旧数据缺字段
            state.setRecordId(recordId);
        }

        if (state.getTopicTracker() == null) {
            state.setTopicTracker(new TopicTracker());
        }
        if (state.getScoreHistory() == null) {
            state.setScoreHistory(new ScoreHistory());
        }
        if (state.getRecentHistory() == null) {
            state.setRecentHistory(new ArrayList<>());
        }
        if (state.getDialogues() == null) {
            state.setDialogues(new ArrayList<>());
        }

        return new Loaded(row, row.cursor(), state);
    }

    private record Loaded(RecordRow row, String cursor, InterviewState state) {
        boolean isFinished() {
            return RecordStatus.FINISHED.getCode().equals(row.status());
        }
    }
}