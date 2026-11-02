package com.livemcq.web;

import com.livemcq.service.JwtService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final JwtService jwt;

    public WebConfig(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(jwt))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**", "/api/health");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // ডেভেলপমেন্টের জন্য খোলা; আসল ব্যবহারে নির্দিষ্ট origin দিন
        registry.addMapping("/api/**").allowedOrigins("*").allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS").allowedHeaders("*");
    }
}
