package com.ke.nhservice.aimianshi.common.config;

import com.ke.nhservice.aimianshi.common.auth.AuthInterceptor;
import com.ke.nhservice.aimianshi.common.log.RequestLogFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 只拦 /api/**，静态页面和前端资源不拦——
        // 否则未登录时连 login.html 都打不开，死锁。
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login");
    }

    /**
     * 访问日志。只挂 /api/*：每个静态页面都要拉 css/js，记进来只会把日志冲淡。
     *
     * order 放最低，让它成为最内层的过滤器——这样读到的是已经处理完的状态码，
     * 而不是还没被异常处理器改写过的 200。
     */
    @Bean
    public FilterRegistrationBean<RequestLogFilter> requestLogFilter() {
        FilterRegistrationBean<RequestLogFilter> bean =
                new FilterRegistrationBean<>(new RequestLogFilter());
        bean.addUrlPatterns("/api/*");
        bean.setOrder(Ordered.LOWEST_PRECEDENCE);
        return bean;
    }
}