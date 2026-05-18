package com.music.reco.domain.repository;

import com.music.reco.domain.entity.UserTrackFeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTrackFeedbackRepository extends JpaRepository<UserTrackFeedbackEntity, Long> {
}
