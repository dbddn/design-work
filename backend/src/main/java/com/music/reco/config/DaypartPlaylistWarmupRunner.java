package com.music.reco.config;

import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.recommendation.service.DaypartPlaylistGenerationCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class DaypartPlaylistWarmupRunner {
    private static final Logger log = LoggerFactory.getLogger(DaypartPlaylistWarmupRunner.class);

    @Bean
    ApplicationRunner warmupDaypartPlaylists(LegacyJdbcRepository legacyJdbcRepository,
                                             DaypartPlaylistGenerationCoordinator coordinator) {
        return args -> {
            List<String> userIds = legacyJdbcRepository.listRecentActiveUserIds(3);
            if (userIds.isEmpty()) {
                log.info("daypart warmup skipped reason=NO_ACTIVE_USERS");
                return;
            }

            for (String userId : userIds) {
                coordinator.enqueue(userId, "default", "neutral", 10, "startup-warmup");
            }
            log.info("daypart warmup enqueued userCount={} users={}", userIds.size(), userIds);
        };
    }
}
