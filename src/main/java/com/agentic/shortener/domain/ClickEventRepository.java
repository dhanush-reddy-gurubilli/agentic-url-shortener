package com.agentic.shortener.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    @Query("select count(c) from ClickEvent c where c.urlId = :urlId and c.occurredAt >= :from and c.occurredAt < :to")
    long countInRange(@Param("urlId") Long urlId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
            select cast(date_trunc('day', occurred_at at time zone 'UTC') as date) as day, count(*) as clicks
            from click_events
            where url_id = :urlId and occurred_at >= :from and occurred_at < :to
            group by 1 order by 1
            """, nativeQuery = true)
    List<DailyClicks> clicksPerDay(@Param("urlId") Long urlId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
            select coalesce(referrer_host, '(direct)') as referrer, count(*) as clicks
            from click_events
            where url_id = :urlId and occurred_at >= :from and occurred_at < :to
            group by 1 order by 2 desc, 1 limit 5
            """, nativeQuery = true)
    List<ReferrerClicks> topReferrers(@Param("urlId") Long urlId, @Param("from") Instant from, @Param("to") Instant to);

    interface DailyClicks {
        java.time.LocalDate getDay();

        long getClicks();
    }

    interface ReferrerClicks {
        String getReferrer();

        long getClicks();
    }
}
