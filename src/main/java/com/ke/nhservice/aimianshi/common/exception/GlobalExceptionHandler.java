package com.ke.nhservice.aimianshi.common.exception;

import com.ke.nhservice.aimianshi.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        // 401 同时改 HTTP 状态，前端才能统一拦截跳登录页；
        // 其余业务错误用 HTTP 200 + code，前端按 code 提示即可
        HttpStatus status = e.getCode() == 401 ? HttpStatus.UNAUTHORIZED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.fail(e.getCode(), e.getMessage()));
    }

    /**
     * 找不到静态文件 / 找不到处理器，都是 404，不是「服务器炸了」。
     *
     * 必须有这条，否则会掉进下面的兜底分支：按 ERROR 打一整条堆栈、回 500。
     * 而浏览器每次打开页面都会自动请求 /favicon.ico，也就是每次刷页面都往日志里
     * 灌一条 ERROR 堆栈——真出问题时反而找不到有用的日志。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e) {
        log.debug("找不到资源: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(404, "资源不存在"));
    }

    /**
     * 上传超过 10MB。这条不是「未预期的异常」，是我们明确设过的限制
     * （`spring.servlet.multipart.max-file-size`），要给用户一句能看懂的话。
     *
     * 不接这条的话会掉进兜底分支，用户看到的是
     * 「服务器内部错误：Maximum upload size exceeded」，外加日志里一整条 ERROR 堆栈。
     * 而简历页上写着「不超过 10MB」——用户照着做的话本不该看到报错。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小限制: {}", e.getMessage());
        return ResponseEntity.ok(ApiResponse.fail(400, "文件超过 10MB 上限，请压缩后再上传"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("未预期的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, "服务器内部错误：" + e.getMessage()));
    }
}