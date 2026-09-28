package com.ke.nhservice.aimianshi.common.auth;

import com.ke.nhservice.aimianshi.common.exception.BizException;

/**
 * 当前登录用户。由 AuthInterceptor 在请求开始时塞入、结束时清理。
 * 用 ThreadLocal 而不是给每个 Controller 方法加参数，是因为绝大多数接口都要用。
 *
 * 用 @RequestAttribute 也可以，但那样每个方法签名都得带一个参数，更啰嗦。
 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId) {
        CURRENT.set(userId);
    }

    public static Long get() {
        Long userId = CURRENT.get();
        if (userId == null) {
            throw BizException.unauthorized("未登录");
        }
        return userId;
    }

    public static void clear() {
        CURRENT.remove();
    }
}