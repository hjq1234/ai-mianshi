package com.ke.nhservice.aimianshi.common.exception;

/**
 * 重试没有意义的错误：4xx（参数错、鉴权错）。
 * 直接往上抛，不进重试循环——省掉 75 秒的无谓等待。
 */
public class NonRetryableException extends RuntimeException {

    public NonRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}