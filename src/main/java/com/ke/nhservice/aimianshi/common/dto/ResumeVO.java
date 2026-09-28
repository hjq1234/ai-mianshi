package com.ke.nhservice.aimianshi.common.dto;

import com.ke.nhservice.aimianshi.biz.resume.Resume;

public record ResumeVO(Long id, String filename, boolean isDefault, long createdAt, String preview) {

    public static ResumeVO of(Resume resume) {
        return new ResumeVO(resume.id(), resume.filename(), resume.isDefault(),
                resume.createdAt(), resume.preview());
    }
}