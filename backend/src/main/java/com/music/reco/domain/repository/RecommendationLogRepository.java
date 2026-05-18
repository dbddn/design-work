package com.music.reco.domain.repository;

import com.music.reco.domain.entity.RecommendationLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationLogRepository extends JpaRepository<RecommendationLogEntity, Long> {
}
