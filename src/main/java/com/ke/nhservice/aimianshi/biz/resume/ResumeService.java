package com.ke.nhservice.aimianshi.biz.resume;

import com.ke.nhservice.aimianshi.common.exception.BizException;
import com.ke.nhservice.aimianshi.wrapper.pdf.PdfTextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    private static final long MAX_BYTES = 10L * 1024 * 1024;

    /**
     * 抽出的正文短于这个长度，就不像一份简历。
     *
     * ★ 实测踩过：招聘平台导出的图片型 PDF（整页是一张图）里，PDFBox 唯一能抽到的
     *   是页面上那串系统编号——比如 40 个字符的 "c46000c8…"。
     *   它**不是空白**，所以只挡 isBlank() 的话这份简历会被收下，
     *   然后出题、改简历都对着这串编号说话，要等烧完一次调用才发现。
     *   门槛压到 100：项目自带的 sample-resume.pdf 只有 233 字符，不能被误伤。
     */
    private static final int MIN_CHARS = 100;

    /**
     * 粘贴进来的正文长度上限。
     *
     * 10 万字远超任何一份简历，所以这条在正常使用中永远碰不到；它的作用是挡住
     * 「误粘了一整本书 / 一大段日志」。**超了直接拒、不截断**：截断会悄悄改掉
     * 用户粘进来的内容，而后面出题和改稿都对着这份东西说话，比报错难查得多。
     */
    private static final int MAX_TEXT_CHARS = 100_000;

    /** 粘贴进来的简历没有文件名，用这个格式按时间起一个显示名 */
    private static final DateTimeFormatter NAME_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

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
        if (content.length() < MIN_CHARS) {
            throw new BizException("这份 PDF 基本没有文字层（只抽到 " + content.length()
                    + " 个字符），多半是图片型 PDF——招聘平台导出的常见这种。"
                    + "当前不支持 OCR，请换一份带文字层的：用 Word / WPS 另存为 PDF 一般就行");
        }

        return store(userId, filename, content);
    }

    /**
     * 直接存一份粘贴进来的简历全文，不经过 PDF。
     *
     * 这条入口是给「原版是 Word，不想为传一份简历去另存为 PDF」准备的，
     * 顺带把「图片型 PDF 抽不出文字」那条死路也绕开了——粘进来的必然是文字。
     */
    public Resume saveText(Long userId, String filename, String content) {
        // 粘进来常带前后的空行和尾随空格，存库前统一剪掉，
        // 免得「明明粘了内容」却因为整段是空白被判成空
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) {
            throw new BizException("请把简历内容粘贴进来");
        }
        if (text.length() < MIN_CHARS) {
            throw new BizException("只粘到 " + text.length() + " 个字符，这份简历太短了（至少要 "
                    + MIN_CHARS + " 个字符）。确认一下是不是只复制到了一行标题");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new BizException("粘贴的内容有 " + text.length() + " 个字符，超过 "
                    + MAX_TEXT_CHARS + " 的上限，请删掉一些再存");
        }

        String name = (filename == null || filename.isBlank())
                ? "粘贴的简历-" + LocalDateTime.now().format(NAME_STAMP)
                : filename.strip();
        log.info("粘贴存简历 | 用户={} 名字={} | {} 字符", userId, name, text.length());
        return store(userId, name, text);
    }

    /** 落库 + 「第一份自动设为默认」。上传和粘贴两条入口都走这里，省得两份实现走偏 */
    private Resume store(Long userId, String filename, String content) {
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