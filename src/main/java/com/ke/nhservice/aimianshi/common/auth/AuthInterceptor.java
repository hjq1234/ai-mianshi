package com.ke.nhservice.aimianshi.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    /** 访问日志过滤器靠这个 request 属性拿到「请求是谁发的」 */
    public static final String ATTR_USER_ID = "app.userId";

    private final TokenUtil tokenUtil;

    public AuthInterceptor(TokenUtil tokenUtil) {
        this.tokenUtil = tokenUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(HEADER);
        String token = header != null && header.startsWith(PREFIX)
                ? header.substring(PREFIX.length()).trim()
                : null;
        // verify 失败会抛 BizException(401)，由 GlobalExceptionHandler 统一转成响应
        Long userId = tokenUtil.verify(token);
        UserContext.set(userId);
        // 顺手存一份到 request 上：ThreadLocal 在 afterCompletion 就清了，
        // 而 Filter 是在整条链路之外读的，读不到 ThreadLocal
        request.setAttribute(ATTR_USER_ID, userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        // 线程池会复用线程，必须清掉，否则下一个请求会串号
        UserContext.clear();
    }
}