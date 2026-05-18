package com.music.reco.assistant.controller;

import com.music.reco.assistant.dto.AssistantChatRequest;
import com.music.reco.assistant.dto.AssistantChatResponse;
import com.music.reco.assistant.service.AssistantService;
import com.music.reco.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping("/chat")
    public ApiResponse<AssistantChatResponse> chat(
            @RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
            @Valid @RequestBody AssistantChatRequest request
    ) {
        return ApiResponse.ok(assistantService.chat(userId, request));
    }
}
