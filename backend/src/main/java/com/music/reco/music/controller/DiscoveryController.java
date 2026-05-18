package com.music.reco.music.controller;

import com.music.reco.common.api.ApiResponse;
import com.music.reco.music.dto.ChartItemDto;
import com.music.reco.music.dto.NeteaseToplistDto;
import com.music.reco.music.dto.TimeMachineNodeDto;
import com.music.reco.music.dto.TimelineNodeDto;
import com.music.reco.music.dto.TrackDto;
import com.music.reco.music.service.DiscoveryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DiscoveryController {
    private final DiscoveryService discoveryService;

    public DiscoveryController(DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @GetMapping("/charts/hot")
    public ApiResponse<List<ChartItemDto>> hotCharts() {
        return ApiResponse.ok(discoveryService.hotCharts());
    }

    @GetMapping("/charts/new")
    public ApiResponse<List<ChartItemDto>> newCharts() {
        return ApiResponse.ok(discoveryService.newCharts());
    }

    @GetMapping("/charts/netease/toplist")
    public ApiResponse<List<NeteaseToplistDto>> neteaseToplists(@RequestParam(defaultValue = "4") int limit,
                                                                 @RequestParam(defaultValue = "5") int trackLimit) {
        return ApiResponse.ok(discoveryService.neteaseToplists(limit, trackLimit));
    }

    @GetMapping("/explore/timeline")
    public ApiResponse<List<TimelineNodeDto>> timeline() {
        return ApiResponse.ok(discoveryService.timeline());
    }

    @GetMapping("/explore/time-machine")
    public ApiResponse<List<TimeMachineNodeDto>> timeMachine() {
        return ApiResponse.ok(discoveryService.timeMachine());
    }

    @GetMapping("/explore/time-machine/tracks")
    public ApiResponse<List<TrackDto>> timeMachineTracks(@RequestParam(required = false) Integer year,
                                                         @RequestParam(required = false) Integer startYear,
                                                         @RequestParam(required = false) Integer endYear,
                                                         @RequestParam(required = false) String genre,
                                                         @RequestParam(defaultValue = "20") int limit) {
        if (year != null) {
            return ApiResponse.ok(discoveryService.timeMachineTracks(year, genre, limit));
        }
        if (startYear != null && endYear != null) {
            return ApiResponse.ok(discoveryService.timeMachineTracksByRange(startYear, endYear, genre, limit));
        }
        return ApiResponse.ok(List.of());
    }
}
