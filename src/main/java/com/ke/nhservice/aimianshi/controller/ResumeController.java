package com.ke.nhservice.aimianshi.controller;

import com.ke.nhservice.aimianshi.biz.resume.Resume;
import com.ke.nhservice.aimianshi.biz.resume.ResumeService;
import com.ke.nhservice.aimianshi.common.auth.UserContext;
import com.ke.nhservice.aimianshi.common.dto.ApiResponse;
import com.ke.nhservice.aimianshi.common.dto.ResumeVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @PostMapping("/upload")
    public ApiResponse<ResumeVO> upload(@RequestParam("file") MultipartFile file) {
        Resume resume = resumeService.upload(UserContext.get(), file);
        return ApiResponse.ok(ResumeVO.of(resume));
    }

    @GetMapping("/list")
    public ApiResponse<List<ResumeVO>> list() {
        List<ResumeVO> list = resumeService.list(UserContext.get()).stream()
                .map(ResumeVO::of)
                .toList();
        return ApiResponse.ok(list);
    }

    @PostMapping("/{id}/default")
    public ApiResponse<Void> setDefault(@PathVariable Long id) {
        resumeService.setDefault(UserContext.get(), id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        resumeService.delete(UserContext.get(), id);
        return ApiResponse.ok();
    }
}