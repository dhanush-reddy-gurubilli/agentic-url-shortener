package com.agentic;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base for tests that need the real application against the real (test) PostgreSQL database. Every test starts from
 * empty tables.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("""
                truncate table click_events, urls, audit_event, clarification, approval_request, decision,
                               artifact, stage_execution, workflow_run restart identity cascade""").update();
    }
}
