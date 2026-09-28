package com.ke.nhservice.aimianshi.common.exception;

/**
 * 可以重试的错误：超时、连接失败、5xx、429。
 * RetryableLlmClient 会捕获它并按 5s/10s/20s/40s 退避重试。
 */
public class RetryableException extends RuntimeException {

    public RetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}