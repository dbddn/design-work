package com.music.reco.recommendation.service;

import com.music.reco.legacy.LegacyJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class DaypartPreferenceSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(DaypartPreferenceSnapshotService.class);
    private static final int SNAPSHOT_LIMIT = 6;
    private static final int SOURCE_WINDOW_DAYS = 30;
    private static final Duration REFRESH_INTERVAL = Duration.ofDays(3);
    private static final List<String> REQUIRED_SLOTS = List.of("morning", "afternoon", "evening", "midnight");

    private final LegacyJdbcRepository legacyJdbcRepository;

    public DaypartPreferenceSnapshotService(LegacyJdbcRepository legacyJdbcRepository) {
        this.legacyJdbcRepository = legacyJdbcRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartupIfMissingOrStale() {
        refreshIfStale("startup");
    }

    @Scheduled(fixedDelayString = "PT72H", initialDelayString = "PT72H")
    public void refreshScheduled() {
        refreshIfStale("scheduled");
    }

    public void refreshIfStale(String trigger) {
        int maxAgeSeconds = Math.toIntExact(REFRESH_INTERVAL.toSeconds());
        if (legacyJdbcRepository.hasFreshDaypartPreferenceSnapshot(maxAgeSeconds)) {
            log.info("daypart preference snapshot fresh trigger={} maxAgeSeconds={}", trigger, maxAgeSeconds);
            return;
        }
        refreshNow(trigger);
    }

    public void refreshNow(String trigger) {
        Map<String, List<String>> genres = ensureSlots(
                legacyJdbcRepository.aggregateGlobalTimeSlotTopGenreLabels(SNAPSHOT_LIMIT)
        );
        Map<String, List<String>> artists = ensureSlots(
                legacyJdbcRepository.aggregateGlobalTimeSlotTopArtistNames(SNAPSHOT_LIMIT)
        );
        legacyJdbcRepository.replaceActiveDaypartPreferenceSnapshot(genres, artists, SOURCE_WINDOW_DAYS);
        log.info("daypart preference snapshot refreshed trigger={} genreSlots={} artistSlots={} sourceWindowDays={}",
                trigger, genres.size(), artists.size(), SOURCE_WINDOW_DAYS);
    }

    private Map<String, List<String>> ensureSlots(Map<String, List<String>> values) {
        for (String slot : REQUIRED_SLOTS) {
            values.computeIfAbsent(slot, key -> List.of());
        }
        return values;
    }
}
