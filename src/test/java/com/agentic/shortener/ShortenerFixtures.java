package com.agentic.shortener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/** Shared builders for shortener tests. */
public final class ShortenerFixtures {

    private ShortenerFixtures() {
    }

    public static ShortenerProperties properties() {
        return new ShortenerProperties("http://short.test", 7, Duration.ofMinutes(10), 1000, 1000, 100, "salt",
                List.of("api", "actuator", "swagger-ui", "admin"));
    }

    /** A clock tests can move forward. */
    public static final class MutableClock extends Clock {
        private volatile Instant now;

        public MutableClock(Instant start) {
            this.now = start;
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
