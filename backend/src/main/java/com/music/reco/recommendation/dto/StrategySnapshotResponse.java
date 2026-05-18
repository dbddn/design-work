package com.music.reco.recommendation.dto;

import java.util.Map;

public record StrategySnapshotResponse(
        boolean coldStart,
        Map<String, Double> hybridWeight,
        double explorationRatio
) {
}
