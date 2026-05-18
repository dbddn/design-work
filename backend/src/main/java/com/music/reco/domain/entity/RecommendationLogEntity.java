package com.music.reco.domain.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "recommendation_log")
public class RecommendationLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 64)
    private String scene;

    @Column(length = 64)
    private String emotion;

    @Column(name = "hybrid_weight", columnDefinition = "json")
    private String hybridWeightJson;

    @Column(name = "strategy_version", length = 32)
    private String strategyVersion;

    @Column(name = "result_track_ids", columnDefinition = "json")
    private String resultTrackIdsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public void setEmotion(String emotion) {
        this.emotion = emotion;
    }

    public void setHybridWeightJson(String hybridWeightJson) {
        this.hybridWeightJson = hybridWeightJson;
    }

    public void setStrategyVersion(String strategyVersion) {
        this.strategyVersion = strategyVersion;
    }

    public void setResultTrackIdsJson(String resultTrackIdsJson) {
        this.resultTrackIdsJson = resultTrackIdsJson;
    }
}
