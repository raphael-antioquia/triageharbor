package com.triageharbor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

class TriageHarborApplicationModeTests {

    @Test
    void missingModeDefaultsToServe() {
        assertEquals(TriageHarborMode.SERVE, TriageHarborMode.parse(null));
    }

    @Test
    void explicitServeModeSelectsServe() {
        assertEquals(TriageHarborMode.SERVE, TriageHarborMode.parse("serve"));
    }

    @Test
    void migrateModeSelectsMigrate() {
        assertEquals(TriageHarborMode.MIGRATE, TriageHarborMode.parse("migrate"));
    }

    @Test
    void blankModeIsRejected() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TriageHarborMode.parse(""));

        assertTrue(exception.getMessage().contains("''"));
    }

    @Test
    void unknownModeIsRejectedAndDiagnosticIncludesOffendingValue() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TriageHarborMode.parse("worker"));

        assertTrue(exception.getMessage().contains("'worker'"));
    }

    @Test
    void serveModeConfiguresServletApplication() {
        SpringApplication application =
                TriageHarborApplication.createApplication(TriageHarborMode.SERVE);

        assertEquals(WebApplicationType.SERVLET, application.getWebApplicationType());
    }

    @Test
    void migrateModeConfiguresNonWebApplication() {
        SpringApplication application =
                TriageHarborApplication.createApplication(TriageHarborMode.MIGRATE);

        assertEquals(WebApplicationType.NONE, application.getWebApplicationType());
    }
}
