package com.music.reco.recommendation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DaypartPlaylistGenerationCoordinator {
    private static final Logger log = LoggerFactory.getLogger(DaypartPlaylistGenerationCoordinator.class);

    private final DaypartPlaylistSnapshotService snapshotService;
    private final ExecutorService executorService;
    private final Set<String> queuedKeys = ConcurrentHashMap.newKeySet();

    public DaypartPlaylistGenerationCoordinator(DaypartPlaylistSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
        this.executorService = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "daypart-generation-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void enqueue(String userId, String scene, String emotion, int limit, String trigger) {
        String jobKey = buildJobKey(userId, scene, emotion);
        if (!queuedKeys.add(jobKey)) {
            log.info("daypart queue skip duplicate userId={} scene={} emotion={} trigger={}",
                    userId, scene, emotion, trigger);
            return;
        }

        executorService.submit(() -> {
            String requestId = "daypart-async-" + System.currentTimeMillis();
            log.info("daypart queue start requestId={} userId={} scene={} emotion={} trigger={}",
                    requestId, userId, scene, emotion, trigger);
            try {
                snapshotService.generateAndStore(requestId, userId, scene, emotion, limit);
                log.info("daypart queue completed requestId={} userId={} scene={} emotion={} status=STORED",
                        requestId, userId, scene, emotion);
            } catch (Exception error) {
                log.error("daypart queue failed requestId={} userId={} scene={} emotion={} message={}",
                        requestId, userId, scene, emotion, error.getMessage(), error);
            } finally {
                queuedKeys.remove(jobKey);
            }
        });
    }

    public boolean isQueued(String userId, String scene, String emotion) {
        return queuedKeys.contains(buildJobKey(userId, scene, emotion));
    }

    private String buildJobKey(String userId, String scene, String emotion) {
        return (userId == null ? "" : userId.trim()) + "|" + (scene == null ? "" : scene.trim()) + "|" + (emotion == null ? "" : emotion.trim());
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }
}
