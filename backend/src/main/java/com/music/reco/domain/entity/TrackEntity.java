package com.music.reco.domain.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "tracks")
public class TrackEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mcp_track_id", unique = true, length = 128)
    private String mcpTrackId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 255)
    private String artist;

    @Column(length = 255)
    private String album;

    @Column(length = 64)
    private String genre;

    @Column(name = "audio_url", length = 255)
    private String audioUrl;

    public Long getId() {
        return id;
    }

    public String getMcpTrackId() {
        return mcpTrackId;
    }

    public void setMcpTrackId(String mcpTrackId) {
        this.mcpTrackId = mcpTrackId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public String getGenre() {
        return genre;
    }

    public void setGenre(String genre) {
        this.genre = genre;
    }

    public String getAudioUrl() {
        return audioUrl;
    }

    public void setAudioUrl(String audioUrl) {
        this.audioUrl = audioUrl;
    }
}
