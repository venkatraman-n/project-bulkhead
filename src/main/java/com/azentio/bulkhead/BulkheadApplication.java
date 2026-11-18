package com.azentio.bulkhead;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BulkheadApplication {

    public static void main(String[] args) {
        SpringApplication.run(BulkheadApplication.class, args);
    }
}
