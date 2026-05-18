package com.music.reco.domain.repository;

import com.music.reco.domain.entity.ListeningHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ListeningHistoryRepository extends JpaRepository<ListeningHistoryEntity, Long> {
    List<ListeningHistoryEntity> findTop40ByUserIdOrderByIdDesc(Long userId);
}
