package com.music.reco.domain.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "listening_history")
public class ListeningHistoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "track_id", nullable = false)
    private Long trackId;

    @Column(name = "played_at", nullable = false)
    private Instant playedAt;

    @Column(name = "play_duration_sec")
    private Integer playDurationSec;

    @Column(name = "completed_flag", nullable = false)
    private Boolean completedFlag;

    @Column(name = "source_type", length = 32)
    private String sourceType;

    @PrePersist
    public void prePersist() {
        if (playedAt == null) {
            playedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getTrackId() {
        return trackId;
    }

    public void setTrackId(Long trackId) {
        this.trackId = trackId;
    }

    public Integer getPlayDurationSec() {
        return playDurationSec;
    }

    public void setPlayDurationSec(Integer playDurationSec) {
        this.playDurationSec = playDurationSec;
    }

    public Boolean getCompletedFlag() {
        return completedFlag;
    }

    public void setCompletedFlag(Boolean completedFlag) {
        this.completedFlag = completedFlag;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public Instant getPlayedAt() {
        return playedAt;
    }
}
