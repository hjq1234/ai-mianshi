package com.ke.nhservice.aimianshi.controller;

import com.ke.nhservice.aimianshi.biz.resume.ResumeReviewService;
import com.ke.nhservice.aimianshi.common.auth.UserContext;
import com.ke.nhservice.aimianshi.common.dto.ApiResponse;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewItemVO;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewRequest;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 简历改稿。
 *
 * ★ 用独立前缀 /api/resume-review，不挂在 /api/resume/{id} 下面：
 *   这两个前缀下各有一条 DELETE /{id}，删的却是完全不同的东西（删改稿 vs 删简历），
 *   放一起早晚看错。所以路径段留给「改稿的 id」，简历的 id 走查询参数（list）。
 */
@RestController
@RequestMapping("/api/resume-review")
public class ResumeReviewController {

    private final ResumeReviewService service;

    public ResumeReviewController(ResumeReviewService service) {
        this.service = service;
    }

    /** 生成。失败不落库，历史里不留半成品 */
    @PostMapping
    public ApiResponse<ResumeReviewVO> generate(@RequestBody ResumeReviewRequest request) {
        return ApiResponse.ok(service.generate(UserContext.get(), request));
    }

    /** 某份简历的历史改稿 */
    @GetMapping
    public ApiResponse<List<ResumeReviewItemVO>> list(@RequestParam Long resumeId) {
        return ApiResponse.ok(service.list(UserContext.get(), resumeId));
    }

    @GetMapping("/{id}")
    public ApiResponse<ResumeReviewVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(UserContext.get(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(UserContext.get(), id);
        return ApiResponse.ok();
    }
}