package com.ke.nhservice.aimianshi.common.log;

import com.ke.nhservice.aimianshi.common.auth.AuthInterceptor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 每个 /api 请求打一行访问日志。
 *
 * 用 Filter 而不是 HandlerInterceptor：拦截器只对「映射到 handler」的请求生效，
 * /api 下没映射上的路径（拼错的 id、被删掉的接口）不会进拦截器，而那正是最需要日志的时候。
 *
 * 只打方法、路径、状态、耗时和用户，不打请求体——回答和简历是候选人的隐私内容。
 * 同一个请求还会在节点侧打出题、评分、分支决策的日志，两边靠 recordId 对上。
 */
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startedAt = System.currentTimeMillis();
        Exception failure = null;
        try {
            chain.doFilter(request, response);
        } catch (Exception e) {
            failure = e;
            throw e;
        } finally {
            // userId 从 request 属性读，不从 UserContext 读：UserContext 是 ThreadLocal，
            // 走到这里时已经被 AuthInterceptor.afterCompletion 清掉了
            Object userId = request.getAttribute(AuthInterceptor.ATTR_USER_ID);
            String query = request.getQueryString();
            log.info("HTTP {} {}{} → {} | {} ms | user={}{}",
                    request.getMethod(),
                    request.getRequestURI(),
                    query == null ? "" : "?" + query,
                    response.getStatus(),
                    System.currentTimeMillis() - startedAt,
                    userId == null ? "-" : userId,
                    failure == null ? "" : " | 异常: " + failure.getMessage());
        }
    }
}