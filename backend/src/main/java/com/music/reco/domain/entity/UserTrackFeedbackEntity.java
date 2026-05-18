package com.music.reco.domain.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "user_track_feedback")
public class UserTrackFeedbackEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "track_id", nullable = false)
    private Long trackId;

    @Column(name = "rating")
    private Integer rating;

    @Column(name = "liked_flag")
    private Boolean likedFlag;

    @Column(name = "skipped_flag")
    private Boolean skippedFlag;

    @Column(name = "feedback_at", nullable = false)
    private Instant feedbackAt;

    @PrePersist
    public void prePersist() {
        if (feedbackAt == null) {
            feedbackAt = Instant.now();
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

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public Boolean getLikedFlag() {
        return likedFlag;
    }

    public void setLikedFlag(Boolean likedFlag) {
        this.likedFlag = likedFlag;
    }

    public Boolean getSkippedFlag() {
        return skippedFlag;
    }

    public void setSkippedFlag(Boolean skippedFlag) {
        this.skippedFlag = skippedFlag;
    }
}
