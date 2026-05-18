package com.music.reco.config;

import com.music.reco.music.service.LocalSongAssetService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MediaResourceConfig implements WebMvcConfigurer {
    private final LocalSongAssetService localSongAssetService;

    public MediaResourceConfig(LocalSongAssetService localSongAssetService) {
        this.localSongAssetService = localSongAssetService;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        localSongAssetService.resourceLocation().ifPresent(location ->
                registry.addResourceHandler("/media-files/**")
                        .addResourceLocations(location)
                        .setCachePeriod(3600));
    }
}
