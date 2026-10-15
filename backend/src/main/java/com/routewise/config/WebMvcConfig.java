package com.routewise.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts every application REST controller under {@code /api/v1}, as the API
 * contract specifies.
 *
 * <p>Done with a path prefix rather than {@code server.servlet.context-path} on
 * purpose: a context path would also move {@code /actuator/health} and the
 * Swagger UI, which container health checks and humans expect at the root.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    public static final String API_PREFIX = "/api/v1";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_PREFIX,
                c -> c.isAnnotationPresent(RestController.class)
                        && c.getPackageName().startsWith("com.routewise"));
    }
}
