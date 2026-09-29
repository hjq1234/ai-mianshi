package com.ke.nhservice.aimianshi.controller;

import com.ke.nhservice.aimianshi.biz.interview.Dialogue;
import com.ke.nhservice.aimianshi.biz.interview.InterviewEngine;
import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.biz.interview.RecordRow;
import com.ke.nhservice.aimianshi.biz.interview.TraceRow;
import com.ke.nhservice.aimianshi.common.auth.UserContext;
import com.ke.nhservice.aimianshi.common.dto.AnswerRequest;
import com.ke.nhservice.aimianshi.common.dto.ApiResponse;
import com.ke.nhservice.aimianshi.common.dto.InterviewDetailVO;
import com.ke.nhservice.aimianshi.common.dto.InterviewStatsVO;
import com.ke.nhservice.aimianshi.common.dto.InterviewTurnVO;
import com.ke.nhservice.aimianshi.common.dto.RecordListItemVO;
import com.ke.nhservice.aimianshi.common.dto.StartInterviewRequest;
import com.ke.nhservice.aimianshi.graph.RunResult;
import com.ke.nhservice.aimianshi.graph.RunStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/interview")
public class InterviewController {

    /** 引擎跑挂时给用户看的话。失败不等于「面试没了」——进度都落库了，可以接着来 */
    private static final Map<RunStatus, String> STATUS_TEXT = Map.of(
            RunStatus.FAILED, "AI 服务暂时不可用，面试进度已保存，可稍后继续",
            RunStatus.STEP_LIMIT, "流程异常中断，进度已保存");

    private final InterviewEngine engine;

    public InterviewController(InterviewEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/start")
    public ApiResponse<InterviewTurnVO> start(@RequestBody StartInterviewRequest request) {
        RunResult<InterviewState> result = engine.start(UserContext.get(),
                request.resumeId(), request.position(), request.company(),
                request.domain(), request.difficulty());
        return ApiResponse.ok(toTurnVO(result));
    }

    @PostMapping("/{id}/answer")
    public ApiResponse<InterviewTurnVO> answer(@PathVariable Long id,
                                               @RequestBody AnswerRequest request) {
        RunResult<InterviewState> result = engine.submitAnswer(
                UserContext.get(), id, request.answer());
        return ApiResponse.ok(toTurnVO(result));
    }

    @PostMapping("/{id}/finish")
    public ApiResponse<InterviewTurnVO> finish(@PathVariable Long id) {
        return ApiResponse.ok(toTurnVO(engine.finish(UserContext.get(), id)));
    }

    @PostMapping("/{id}/resume")
    public ApiResponse<InterviewTurnVO> resume(@PathVariable Long id) {
        return ApiResponse.ok(toTurnVO(engine.resume(UserContext.get(), id)));
    }

    /** 刷新页面后拉当前状态，不推进图 */
    @GetMapping("/{id}/state")
    public ApiResponse<InterviewTurnVO> state(@PathVariable Long id) {
        Long userId = UserContext.get();
        InterviewState state = engine.loadState(userId, id);
        RecordRow row = engine.requireRecord(userId, id);
        return ApiResponse.ok(toTurnVO(state, row.status(), null));
    }

    @GetMapping("/list")
    public ApiResponse<List<RecordListItemVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = UserContext.get();
        List<RecordRow> rows = engine.list(userId, page, size);

        // 一次查完各场面试的答题数，别在循环里逐条 count
        Map<Long, Integer> counts = engine.dialogueCounts(
                rows.stream().map(RecordRow::id).toList());

        List<RecordListItemVO> items = rows.stream()
                .map(row -> new RecordListItemVO(
                        row.id(), row.position(), row.company(), row.domain(),
                        row.difficulty(), row.status(), row.totalScore(),
                        row.createdAt(), row.updatedAt(),
                        counts.getOrDefault(row.id(), 0)))
                .toList();
        return ApiResponse.ok(items);
    }

    /** 复盘详情：逐题评分 + 决策链，复盘页只用这一个接口 */
    @GetMapping("/{id}/detail")
    public ApiResponse<InterviewDetailVO> detail(@PathVariable Long id) {
        Long userId = UserContext.get();
        RecordRow row = engine.requireRecord(userId, id);

        List<InterviewDetailVO.DialogueVO> dialogues = engine.dialogues(id).stream()
                .map(InterviewController::toDialogueVO)
                .toList();
        List<InterviewDetailVO.TraceVO> traces = engine.traces(id).stream()
                .map(InterviewController::toTraceVO)
                .toList();

        // error 从 state_json 里取：EndNode 把「为什么提前结束」写在了那里
        InterviewState state = engine.loadState(userId, id);

        return ApiResponse.ok(new InterviewDetailVO(
                row.id(), row.position(), row.company(), row.domain(), row.difficulty(),
                row.status(), row.totalScore(), row.report(), state.getError(),
                row.createdAt(), row.updatedAt(), dialogues, traces));
    }

    @GetMapping("/{id}/trace")
    public ApiResponse<List<InterviewDetailVO.TraceVO>> trace(@PathVariable Long id) {
        engine.requireRecord(UserContext.get(), id);
        return ApiResponse.ok(engine.traces(id).stream()
                .map(InterviewController::toTraceVO)
                .toList());
    }

    /**
     * 历史均分，复盘页雷达图拿它做对比。
     * ★ 没有 {id}：它是「所有已完成场次」的聚合，不属于任何一场。
     */
    @GetMapping("/stats")
    public ApiResponse<InterviewStatsVO> stats() {
        return ApiResponse.ok(engine.stats(UserContext.get()));
    }

    // ────────────────────────── 转换 ──────────────────────────

    private InterviewTurnVO toTurnVO(RunResult<InterviewState> result) {
        // 失败和超步数都保持 in_progress：进度已落库，用户还能继续，不该显示成「已结束」
        String status = result.status() == RunStatus.FINISHED
                ? "finished" : "in_progress";
        return toTurnVO(result.state(), status, result.status());
    }

    private InterviewTurnVO toTurnVO(InterviewState state, String status, RunStatus runStatus) {
        boolean finished = "finished".equals(status);
        var tracker = state.getTopicTracker();

        // ★ 「上一题的反馈」从已落库的最后一题读，而不是从 state.evalResult 读。
        // 原因有二：
        //  1. QuestionNode 出下一题时会把 evalResult 清掉（那是为了本轮状态干净），
        //     等到图在 wait_answer 挂起时，上一题的评分已经不在 state 里了。
        //  2. /state 这个接口压根不跑图，state 完全来自反序列化的快照——
        //     用快照里的瞬时字段，用户答完题一刷新就会看到反馈消失。
        // 逐题记录本来就是「答过什么」的唯一事实来源，读它也不会和复盘页对不上。
        Dialogue last = lastDialogue(state.getRecordId());

        String error = state.getError();
        if (error == null && runStatus != null) {
            error = STATUS_TEXT.get(runStatus);
        }

        return new InterviewTurnVO(
                state.getRecordId(),
                status,
                finished,
                state.getQuestionIndex(),
                state.getMaxQuestions(),
                state.getQuestionText(),
                tracker == null ? null : tracker.getCurrentTopic(),
                state.getCurrentDifficulty() == null ? null : state.getCurrentDifficulty().getLabel(),
                last == null ? null : last.getScore(),
                last == null ? null : last.getDimensions(),
                last == null ? null : last.getComment(),
                last == null ? null : last.getNextAction(),
                state.getScoreHistory() == null ? null : state.getScoreHistory().average(),
                finished ? state.getReport() : null,
                error,
                last == null ? null : last.getSeq(),
                last == null ? null : last.getTopic(),
                last == null ? null : last.getDifficulty(),
                last == null ? null : last.getQuestion(),
                last == null ? null : last.getAnswer());
    }

    private Dialogue lastDialogue(Long recordId) {
        if (recordId == null) {
            return null;
        }
        List<Dialogue> dialogues = engine.dialogues(recordId);
        return dialogues.isEmpty() ? null : dialogues.get(dialogues.size() - 1);
    }

    private static InterviewDetailVO.DialogueVO toDialogueVO(Dialogue d) {
        return new InterviewDetailVO.DialogueVO(
                d.getSeq(), d.getTopic(), d.getDifficulty(), d.getQuestion(), d.getAnswer(),
                d.getScore(), d.getDimensions(), d.getComment(), d.getNextAction(), d.getNextTopic());
    }

    private static InterviewDetailVO.TraceVO toTraceVO(TraceRow t) {
        return new InterviewDetailVO.TraceVO(
                t.seq(), t.round(), t.nodeName(), t.nodeType(),
                t.fromNode(), t.toNode(), t.costMs(), t.status());
    }
}