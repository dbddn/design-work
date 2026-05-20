package com.music.reco.recommendation.strategy;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class HybridWeightStrategyService {

    public StrategySnapshot generate(String userId, String scene, String emotion, int playCount30d, double skipRate30d) {
        boolean coldStart = playCount30d < 30;
        Map<String, Double> weight = new LinkedHashMap<>();
        double explorationRatio;

        if (playCount30d == 0) {
            weight.put("popular", 0.20);
            weight.put("content", 0.55);
            weight.put("scene", 0.20);
            weight.put("collaborative", 0.05);
            explorationRatio = 0.24;
        } else if (playCount30d < 30) {
            weight.put("popular", 0.14);
            weight.put("content", 0.42);
            weight.put("scene", 0.22);
            weight.put("collaborative", 0.22);
            explorationRatio = 0.28;
        } else if (playCount30d < 80) {
            double sceneWeight = "commute".equalsIgnoreCase(scene) ? 0.24 : 0.20;
            double collaborativeWeight = skipRate30d > 0.35 ? 0.30 : 0.36;
            double contentWeight = 0.26;
            double popularWeight = Math.max(0.10, 1.0 - sceneWeight - collaborativeWeight - contentWeight);
            weight.put("popular", popularWeight);
            weight.put("content", contentWeight);
            weight.put("scene", sceneWeight);
            weight.put("collaborative", collaborativeWeight);
            explorationRatio = skipRate30d > 0.35 ? 0.22 : 0.16;
        } else {
            double sceneWeight = "commute".equalsIgnoreCase(scene) ? 0.25 : 0.20;
            double collabWeight = skipRate30d > 0.35 ? 0.40 : 0.50;
            double contentWeight = 0.18;
            double popularWeight = Math.max(0.10, 1.0 - sceneWeight - collabWeight - contentWeight);
            weight.put("popular", popularWeight);
            weight.put("content", contentWeight);
            weight.put("scene", sceneWeight);
            weight.put("collaborative", collabWeight);
            explorationRatio = skipRate30d > 0.35 ? 0.14 : 0.10;
        }

        if ("sad".equalsIgnoreCase(emotion)) {
            weight.computeIfPresent("scene", (k, v) -> Math.min(0.35, v + 0.05));
            normalize(weight);
        }
        return new StrategySnapshot(coldStart, weight, explorationRatio);
    }

    private void normalize(Map<String, Double> weight) {
        double total = weight.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total == 0) {
            return;
        }
        weight.replaceAll((k, v) -> v / total);
    }
}
