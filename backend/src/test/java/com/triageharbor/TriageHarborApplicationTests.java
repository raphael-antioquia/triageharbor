package com.triageharbor;

import com.triageharbor.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TriageHarborApplicationTests {

    @Test
    void contextLoads() {}
}
