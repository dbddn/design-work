package com.music.reco.music.service;

import com.music.reco.legacy.LegacyJdbcRepository;
import com.music.reco.music.dto.ChartItemDto;
import com.music.reco.music.dto.NeteaseToplistDto;
import com.music.reco.music.dto.TimeMachineNodeDto;
import com.music.reco.music.dto.TimelineNodeDto;
import com.music.reco.music.dto.TrackDto;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DiscoveryService {
    private final LegacyJdbcRepository legacyJdbcRepository;
    private final NeteaseChartService neteaseChartService;

    public DiscoveryService(LegacyJdbcRepository legacyJdbcRepository, NeteaseChartService neteaseChartService) {
        this.legacyJdbcRepository = legacyJdbcRepository;
        this.neteaseChartService = neteaseChartService;
    }

    public List<ChartItemDto> hotCharts() {
        return legacyJdbcRepository.hotCharts(20);
    }

    public List<ChartItemDto> newCharts() {
        return legacyJdbcRepository.newCharts(20);
    }

    public List<NeteaseToplistDto> neteaseToplists(int limit, int trackLimit) {
        return neteaseChartService.listToplists(limit, trackLimit);
    }

    public List<TimelineNodeDto> timeline() {
        return legacyJdbcRepository.timeline();
    }

    public List<TimeMachineNodeDto> timeMachine() {
        return legacyJdbcRepository.timeMachine();
    }

    public List<TrackDto> timeMachineTracks(Integer year, String genre, int limit) {
        return legacyJdbcRepository.listTimeMachineTracks(year, genre, limit);
    }

    public List<TrackDto> timeMachineTracksByRange(Integer startYear, Integer endYear, String genre, int limit) {
        return legacyJdbcRepository.listTimeMachineTracksByRange(startYear, endYear, genre, limit);
    }
}
