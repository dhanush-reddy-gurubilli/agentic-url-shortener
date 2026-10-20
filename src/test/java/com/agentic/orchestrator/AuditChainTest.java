package com.agentic.orchestrator;

import com.agentic.IntegrationTest;
import com.agentic.orchestrator.audit.AuditService;
import com.agentic.orchestrator.domain.Records.AuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class AuditChainTest extends IntegrationTest {

    @Autowired
    AuditService audit;

    private void record(int n) {
        for (int i = 0; i < n; i++) {
            audit.record(null, null, "test", "EVENT_" + i, "payload " + i);
        }
    }

    @Test
    void anEmptyTrailIsValid() {
        assertThat(audit.verify().valid()).isTrue();
        assertThat(audit.verify().eventsChecked()).isZero();
    }

    @Test
    void eachEventChainsToThePreviousOne() {
        record(5);
        List<AuditEvent> events = audit.all();
        assertThat(events.getFirst().prevHash()).isEqualTo("0".repeat(64));
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).prevHash()).isEqualTo(events.get(i - 1).hash());
        }
        assertThat(audit.verify().valid()).isTrue();
        assertThat(audit.verify().eventsChecked()).isEqualTo(5);
    }

    @Test
    void editingAnEventsPayloadBreaksTheChainAtThatEvent() {
        record(5);
        long victim = audit.all().get(2).id();
        jdbc.sql("update audit_event set payload = 'tampered' where id = :id").param("id", victim).update();

        AuditService.VerifyResult result = audit.verify();

        assertThat(result.valid()).isFalse();
        assertThat(result.firstBrokenEventId()).isEqualTo(victim);
    }

    @Test
    void deletingAnEventFromTheMiddleIsDetected() {
        record(5);
        List<AuditEvent> events = audit.all();
        jdbc.sql("delete from audit_event where id = :id").param("id", events.get(2).id()).update();

        AuditService.VerifyResult result = audit.verify();

        assertThat(result.valid()).isFalse();
        assertThat(result.firstBrokenEventId()).isEqualTo(events.get(3).id());
    }

    @Test
    void rewritingAnEventAndItsHashStillBreaksTheNextLink() {
        record(4);
        List<AuditEvent> events = audit.all();
        jdbc.sql("update audit_event set payload = 'forged', hash = :h where id = :id")
                .param("h", "a".repeat(64)).param("id", events.get(1).id()).update();

        assertThat(audit.verify().valid()).isFalse();
    }

    @Test
    void concurrentWritersStillProduceOneUnbrokenChain() throws Exception {
        int writers = 8;
        int each = 10;
        var pool = Executors.newFixedThreadPool(writers);
        CountDownLatch go = new CountDownLatch(1);
        UUID run = null;
        for (int w = 0; w < writers; w++) {
            int writer = w;
            pool.submit(() -> {
                go.await();
                for (int i = 0; i < each; i++) {
                    audit.record(run, null, "writer-" + writer, "CONCURRENT", "event " + i);
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(audit.verify().valid()).isTrue();
        assertThat(audit.verify().eventsChecked()).isEqualTo(writers * each);
    }
}
