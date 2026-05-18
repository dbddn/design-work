package com.music.reco.recommendation.strategy;

import java.util.Map;

public record StrategySnapshot(
        boolean coldStart,
        Map<String, Double> hybridWeight,
        double explorationRatio
) {
}
