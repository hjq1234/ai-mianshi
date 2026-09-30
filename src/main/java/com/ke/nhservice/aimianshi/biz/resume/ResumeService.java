package com.ke.nhservice.aimianshi.biz.resume;

import com.ke.nhservice.aimianshi.common.exception.BizException;
import com.ke.nhservice.aimianshi.wrapper.pdf.PdfTextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final ResumeDao resumeDao;
    private final ResumeReviewDao reviewDao;

    public ResumeService(ResumeDao resumeDao, ResumeReviewDao reviewDao) {
        this.resumeDao = resumeDao;
        this.reviewDao = reviewDao;
    }

    public Resume upload(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        String filename = file.getOriginalFilename() == null ? "resume.pdf" : file.getOriginalFilename();
        if (!filename.toLowerCase().endsWith(".pdf")) {
            throw new BizException("只支持 PDF 格式的简历");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException("文件超过 10MB 上限");
        }

        String content;
        try {
            content = PdfTextExtractor.extract(file.getBytes());
        } catch (IOException e) {
            throw new BizException("PDF 解析失败，请确认文件未损坏或加密：" + e.getMessage());
        }
        if (content.isBlank()) {
            throw new BizException("没能从这份 PDF 里提取到文字，可能是扫描件（图片型 PDF），当前不支持 OCR");
        }

        long id = resumeDao.insert(userId, filename, content);
        // 第一份简历自动设为默认，省掉用户一次点击
        if (resumeDao.countByUser(userId) == 1) {
            resumeDao.setDefault(userId, id);
        }
        return resumeDao.findById(id).orElseThrow(() -> new BizException("简历保存后读取失败"));
    }

    public List<Resume> list(Long userId) {
        return resumeDao.listByUser(userId);
    }

    public void setDefault(Long userId, Long resumeId) {
        requireOwned(userId, resumeId);
        resumeDao.setDefault(userId, resumeId);
    }

    /**
     * 删一份简历，连带删掉它的全部改稿。
     *
     * ★ 事务必须在 service 这一层：t_resume 是物理删、**没有外键级联**，
     *   两张表的删除要么一起成功要么一起失败（否则简历没了、改稿还在库里当孤儿）。
     *   ResumeDao.setDefault 那个 @Transactional 是「一张表的两条语句」，这里是两张表。
     */
    @Transactional
    public void delete(Long userId, Long resumeId) {
        requireOwned(userId, resumeId);
        int removed = reviewDao.deleteByResume(resumeId);
        resumeDao.delete(resumeId);
        log.info("删除简历 | 简历={} 用户={} | 连带删掉 {} 条改稿", resumeId, userId, removed);
    }

    /** 面试开始时解析简历文本用 */
    public Resume requireOwned(Long userId, Long resumeId) {
        Resume resume = resumeDao.findById(resumeId)
                .orElseThrow(() -> BizException.notFound("简历不存在"));
        if (!resume.userId().equals(userId)) {
            throw BizException.notFound("简历不存在");
        }
        return resume;
    }

    /** 面试开始时若没指定简历，就用默认简历；一份都没有则返回 null（允许无简历面试） */
    public Resume defaultOrNull(Long userId) {
        return resumeDao.findDefault(userId).orElse(null);
    }
}