package com.music.reco.assistant.dto;

import java.util.List;

public record AiCompletionResult(
        String content,
        List<String> reasoningSummary
) {
}
