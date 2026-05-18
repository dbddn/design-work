package com.music.reco.analytics.controller;

import com.music.reco.analytics.dto.GenreDistributionDto;
import com.music.reco.analytics.dto.HeatmapPointDto;
import com.music.reco.analytics.service.AnalyticsService;
import com.music.reco.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/heatmap")
    public ApiResponse<List<HeatmapPointDto>> heatmap(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
                                                      @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(analyticsService.heatmap(userId, days));
    }

    @GetMapping("/genres")
    public ApiResponse<List<GenreDistributionDto>> genres(@RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
                                                          @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(analyticsService.genreDistribution(userId, days));
    }
}
