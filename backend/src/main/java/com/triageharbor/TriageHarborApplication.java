package com.triageharbor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class TriageHarborApplication {

    private static final String MODE_ENVIRONMENT_VARIABLE = "TRIAGEHARBOR_MODE";

    public static void main(String[] args) {
        TriageHarborMode mode =
                TriageHarborMode.parse(System.getenv(MODE_ENVIRONMENT_VARIABLE));

        SpringApplication application = createApplication(mode);

        if (mode == TriageHarborMode.MIGRATE) {
            try (ConfigurableApplicationContext context = application.run(args)) {
                MigrationVerifier.verify(context);
            }

            return;
        }

        application.run(args);
    }

    static SpringApplication createApplication(TriageHarborMode mode) {
        SpringApplication application = new SpringApplication(TriageHarborApplication.class);

        application.setWebApplicationType(
                switch (mode) {
                    case SERVE -> WebApplicationType.SERVLET;
                    case MIGRATE -> WebApplicationType.NONE;
                });

        return application;
    }
}
