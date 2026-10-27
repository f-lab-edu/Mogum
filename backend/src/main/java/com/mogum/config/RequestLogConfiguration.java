package com.mogum.config;

import com.mogum.common.logging.RequestLogFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class RequestLogConfiguration {
    @Bean
    public FilterRegistrationBean<RequestLogFilter> requestLogFilter() {
        var registration = new FilterRegistrationBean<>(new RequestLogFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
        return registration;
    }

    @Bean
    public ServletListenerRegistrationBean<RequestLogFilter> requestLogCompletion(
            FilterRegistrationBean<RequestLogFilter> requestLogFilter) {
        return new ServletListenerRegistrationBean<>(requestLogFilter.getFilter());
    }
}
