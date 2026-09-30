package com.ke.nhservice.aimianshi.biz.resume;

import com.ke.nhservice.aimianshi.biz.interview.Dialogue;
import com.ke.nhservice.aimianshi.biz.interview.InterviewEngine;
import com.ke.nhservice.aimianshi.biz.interview.RecordRow;
import com.ke.nhservice.aimianshi.biz.interview.prompt.PromptLoader;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewItemVO;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewRequest;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewTarget;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewVO;
import com.ke.nhservice.aimianshi.common.exception.BizException;
import com.ke.nhservice.aimianshi.wrapper.llm.ChatMessage;
import com.ke.nhservice.aimianshi.wrapper.llm.LlmClient;
import com.ke.nhservice.aimianshi.wrapper.llm.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ResumeReviewService {

    private static final Logger log = LoggerFactory.getLogger(ResumeReviewService.class);

    /** 提示词文件名，对应 resources/prompts/resume_review.md */
    private static final String PROMPT = "resume_review";

    private final ResumeReviewDao dao;
    private final ResumeService resumeService;
    private final InterviewEngine engine;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final LlmProperties llmProps;

    public ResumeReviewService(ResumeReviewDao dao,
                               ResumeService resumeService,
                               InterviewEngine engine,
                               LlmClient llm,
                               PromptLoader prompts,
                               LlmProperties llmProps) {
        this.dao = dao;
        this.resumeService = resumeService;
        this.engine = engine;
        this.llm = llm;
        this.prompts = prompts;
        this.llmProps = llmProps;
    }

    /**
     * 生成一次改稿。**一次** LLM 调用，产物是纯 Markdown（见 prompts/resume_review.md）。
     *
     * LLM 挂了就抛出去让页面 toast，**不落库**：历史里不留半成品
     * （和面试那条链一致——评分挂了也不会写一条空对话）。
     */
    public ResumeReviewVO generate(Long userId, ResumeReviewRequest request) {
        if (request == null || request.resumeId() == null) {
            throw new BizException("请先选一份简历");
        }
        List<ResumeReviewTarget> targets = cleanTargets(request.targets());
        Resume resume = resumeService.requireOwned(userId, request.resumeId());
        List<Long> interviewIds = request.interviewIds() == null
                ? List.of() : request.interviewIds();

        // 归属校验走 engine.requireRecord（内部是 loadOwned）：别人的 id 在这里就 404 了，
        // 不会有机会被喂进 prompt —— 把别人的面试记录拼进 prompt 是最不该发生的事
        List<RecordRow> records = new ArrayList<>();
        Map<Long, List<Dialogue>> byRecord = new LinkedHashMap<>();
        for (Long id : interviewIds) {
            records.add(engine.requireRecord(userId, id));
            // 逐场一条查询：选中的场次是个位数，且 listDialogues 走 record_id 索引，
            // 不值得为它引入批量方法（listDialoguesByRecords 的 SELECT 里没有 record_id 列，
            // 拿它拼不出「哪条属于哪场」）
            byRecord.put(id, engine.dialogues(id));
        }

        String prompt = prompts.render(PROMPT, Map.of(
                "targets", ResumeReviewMaterial.targets(targets),
                "interviewMaterial", ResumeReviewMaterial.interviews(records, byRecord),
                "resume", resume.content()));

        // 简历正文和 prompt 都不打：简历是候选人的隐私内容，和 RequestLogFilter 的口径一致
        log.info("开始生成简历改稿 | 简历={} 用户={} | 目标岗位={} 参考面试={} 场 | 简历 {} 字符",
                resume.id(), userId, targets.size(), interviewIds.size(), resume.content().length());

        long startedAt = System.currentTimeMillis();
        LlmClient.Reply reply = llm.chatDetailed(List.of(ChatMessage.user(prompt)));
        long cost = System.currentTimeMillis() - startedAt;

        String markdown = reply.content().strip();
        int suggestionCount = ResumeReviewParser.count(markdown);
        long reviewId = dao.insert(userId, resume.id(), markdown, suggestionCount,
                ResumeReviewParser.writeTargets(targets),
                interviewIds.isEmpty() ? null : joinIds(interviewIds),
                llmProps.getModel(),
                reply.truncated());

        log.info("简历改稿完成 | 改稿={} 简历={} 用户={} | {} ms | 建议 {} 条 | {} 字符 | 模型={}{}",
                reviewId, resume.id(), userId, cost, suggestionCount, markdown.length(),
                llmProps.getModel(),
                reply.truncated() ? " | ★ 输出被长度上限截断（app.llm.max-tokens 可调大）" : "");

        // 返回库里那一份，不是内存里那一份：页面上看到的和存下来的必然一致
        return detail(userId, reviewId);
    }

    /** 某份简历的历史改稿。先校验简历归属——别人的 resumeId 该 404，而不是返回一个空数组 */
    public List<ResumeReviewItemVO> list(Long userId, Long resumeId) {
        if (resumeId == null) {
            throw new BizException("请先选一份简历");
        }
        resumeService.requireOwned(userId, resumeId);
        return dao.listByResume(resumeId).stream()
                .map(r -> new ResumeReviewItemVO(
                        r.id(), r.createdAt(), r.suggestionCount(),
                        ResumeReviewParser.parseTargets(r.targetsJson()),
                        parseIds(r.interviewIds()).size(), r.model(), r.truncated()))
                .toList();
    }

    public ResumeReviewVO detail(Long userId, Long reviewId) {
        ResumeReview r = requireOwned(userId, reviewId);
        Resume resume = resumeService.requireOwned(userId, r.resumeId());
        return new ResumeReviewVO(
                r.id(), r.resumeId(), resume.filename(), r.createdAt(), r.model(), r.truncated(),
                ResumeReviewParser.parseTargets(r.targetsJson()),
                parseIds(r.interviewIds()),
                r.markdown(),
                ResumeReviewParser.document(r.markdown()),
                ResumeReviewParser.suggestions(r.markdown()));
    }

    public void delete(Long userId, Long reviewId) {
        requireOwned(userId, reviewId);
        dao.delete(reviewId);
        log.info("删除简历改稿 | 改稿={} 用户={}", reviewId, userId);
    }

    // ────────────────────────── 内部 ──────────────────────────

    /**
     * 归属校验。不存在和不是你的都统一 notFound，和 ResumeService.requireOwned 一个口径
     * （不区分「没有」和「不是你的」，避免探测别人的 id）。
     */
    private ResumeReview requireOwned(Long userId, Long reviewId) {
        ResumeReview review = dao.findById(reviewId)
                .orElseThrow(() -> BizException.notFound("这份改稿不存在"));
        if (!review.userId().equals(userId)) {
            throw BizException.notFound("这份改稿不存在");
        }
        return review;
    }

    /**
     * 岗位名必填、两头去空白，JD 空白算没填。
     * 前端也拦一道，但后端不能指望前端——一个空岗位名会让整次生成白跑 15-40 秒。
     */
    private static List<ResumeReviewTarget> cleanTargets(List<ResumeReviewTarget> raw) {
        List<ResumeReviewTarget> out = new ArrayList<>();
        for (ResumeReviewTarget t : raw == null ? List.<ResumeReviewTarget>of() : raw) {
            String title = t == null || t.title() == null ? "" : t.title().strip();
            if (title.isEmpty()) {
                continue;
            }
            String jd = t.jd() == null || t.jd().isBlank() ? null : t.jd().strip();
            out.add(new ResumeReviewTarget(title, jd));
        }
        if (out.isEmpty()) {
            throw new BizException("至少填一个目标岗位");
        }
        return out;
    }

    /** interview_ids 存成逗号分隔串（和 t_resume 那些列一样，不值得为它开一张关联表） */
    private static String joinIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static List<Long> parseIds(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
    }
}