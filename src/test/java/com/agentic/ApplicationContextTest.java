package com.agentic;

import com.agentic.orchestrator.agent.AgentRegistry;
import com.agentic.orchestrator.engine.WorkflowTemplateLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationContextTest extends IntegrationTest {

    @Autowired
    WorkflowTemplateLoader templates;
    @Autowired
    AgentRegistry agents;

    @Test
    void contextLoadsAndMigrationsApply() {
        Integer tables = jdbc.sql("""
                select count(*) from information_schema.tables
                where table_schema = current_schema()
                  and table_name in ('urls', 'click_events', 'workflow_run', 'stage_execution', 'artifact',
                                     'decision', 'approval_request', 'clarification', 'audit_event')""")
                .query(Integer.class).single();
        assertThat(tables).isEqualTo(9);
    }

    @Test
    void everyAgentAndGateNamedByTheWorkflowExists() {
        assertThat(templates.template().stages()).isNotEmpty();
        templates.template().stages().forEach(s -> assertThat(agents.has(s.agent())).as(s.key()).isTrue());
    }
}
