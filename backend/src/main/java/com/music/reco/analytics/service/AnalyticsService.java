package com.music.reco.analytics.service;

import com.music.reco.analytics.dto.GenreDistributionDto;
import com.music.reco.analytics.dto.HeatmapPointDto;
import com.music.reco.legacy.LegacyJdbcRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnalyticsService {
    private final LegacyJdbcRepository legacyJdbcRepository;

    public AnalyticsService(LegacyJdbcRepository legacyJdbcRepository) {
        this.legacyJdbcRepository = legacyJdbcRepository;
    }

    public List<HeatmapPointDto> heatmap(String userId, int days) {
        return legacyJdbcRepository.heatmap(userId, days);
    }

    public List<GenreDistributionDto> genreDistribution(String userId, int days) {
        return legacyJdbcRepository.genreDistribution(userId, days);
    }
}
