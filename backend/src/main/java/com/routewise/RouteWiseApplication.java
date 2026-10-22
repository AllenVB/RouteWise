package com.routewise;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * RouteWise backend.
 *
 * <p>Owns all persistent data. The Python service is stateless with respect to
 * business data and is called over REST; it never touches the database.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RouteWiseApplication {

    public static void main(String[] args) {
        SpringApplication.run(RouteWiseApplication.class, args);
    }
}
