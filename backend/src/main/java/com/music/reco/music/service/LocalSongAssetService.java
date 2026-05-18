package com.music.reco.music.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class LocalSongAssetService {
    private static final Pattern TRACK_ID_PATTERN = Pattern.compile(" - (\\d+)\\.[^.]+$");
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".mp3", ".flac", ".wav", ".m4a");

    private final Map<Long, Path> indexedSongs = new ConcurrentHashMap<>();
    private final Map<Long, Optional<EmbeddedCover>> coverCache = new ConcurrentHashMap<>();
    private final Path songsDirectory;

    public LocalSongAssetService() {
        this.songsDirectory = resolveSongsDirectory();
    }

    @PostConstruct
    void init() {
        rebuildIndex();
    }

    public AudioAsset resolveTrackAsset(Long trackId, String fallbackArtworkUrl) {
        Path songPath = findSongPath(trackId);
        if (songPath == null) {
            return new AudioAsset(null, blankToNull(fallbackArtworkUrl), false);
        }

        String encodedFileName = UriUtils.encodePathSegment(songPath.getFileName().toString(), StandardCharsets.UTF_8);
        String artworkUrl = readEmbeddedCover(trackId).isPresent()
                ? "/api/media/songs/" + trackId + "/cover"
                : blankToNull(fallbackArtworkUrl);
        return new AudioAsset("/media-files/" + encodedFileName, artworkUrl, true);
    }

    public Optional<EmbeddedCover> readEmbeddedCover(Long trackId) {
        if (trackId == null) {
            return Optional.empty();
        }
        return coverCache.computeIfAbsent(trackId, this::extractEmbeddedCoverSafely);
    }

    public Optional<String> resourceLocation() {
        if (!Files.isDirectory(songsDirectory)) {
            return Optional.empty();
        }
        String location = songsDirectory.toAbsolutePath().normalize().toUri().toString();
        return Optional.of(location.endsWith("/") ? location : location + "/");
    }

    private Optional<EmbeddedCover> extractEmbeddedCoverSafely(Long trackId) {
        Path songPath = findSongPath(trackId);
        if (songPath == null) {
            return Optional.empty();
        }
        try {
            String fileName = songPath.getFileName().toString().toLowerCase();
            if (fileName.endsWith(".mp3")) {
                return extractMp3Cover(songPath);
            }
            if (fileName.endsWith(".flac")) {
                return extractFlacCover(songPath);
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private Optional<EmbeddedCover> extractMp3Cover(Path songPath) throws IOException {
        try (InputStream inputStream = Files.newInputStream(songPath)) {
            byte[] header = inputStream.readNBytes(10);
            if (header.length < 10 || header[0] != 'I' || header[1] != 'D' || header[2] != '3') {
                return Optional.empty();
            }

            int majorVersion = header[3] & 0xFF;
            int tagSize = syncSafeInt(header, 6);
            byte[] tagData = inputStream.readNBytes(tagSize);
            int offset = 0;
            while (offset + 10 <= tagData.length) {
                String frameId = new String(tagData, offset, 4, StandardCharsets.ISO_8859_1);
                int frameSize = majorVersion == 4 ? syncSafeInt(tagData, offset + 4) : bigEndianInt(tagData, offset + 4);
                if (frameSize <= 0 || offset + 10 + frameSize > tagData.length) {
                    break;
                }
                if ("APIC".equals(frameId)) {
                    byte[] frameData = new byte[frameSize];
                    System.arraycopy(tagData, offset + 10, frameData, 0, frameSize);
                    return parseApicFrame(frameData);
                }
                offset += 10 + frameSize;
            }
        }
        return Optional.empty();
    }

    private Optional<EmbeddedCover> parseApicFrame(byte[] frameData) {
        if (frameData.length < 4) {
            return Optional.empty();
        }
        int textEncoding = frameData[0] & 0xFF;
        int mimeEnd = indexOfZero(frameData, 1, 1);
        if (mimeEnd < 0) {
            return Optional.empty();
        }
        String mimeType = new String(frameData, 1, mimeEnd - 1, StandardCharsets.ISO_8859_1);
        int cursor = mimeEnd + 1;
        if (cursor >= frameData.length) {
            return Optional.empty();
        }
        cursor += 1;
        int descriptionEnd = indexOfDescriptionTerminator(frameData, cursor, textEncoding);
        if (descriptionEnd < 0) {
            return Optional.empty();
        }
        int terminatorLength = terminatorLength(textEncoding);
        int imageStart = descriptionEnd + terminatorLength;
        if (imageStart >= frameData.length) {
            return Optional.empty();
        }
        byte[] imageData = new byte[frameData.length - imageStart];
        System.arraycopy(frameData, imageStart, imageData, 0, imageData.length);
        return imageData.length == 0 ? Optional.empty() : Optional.of(new EmbeddedCover(mimeType, imageData));
    }

    private Optional<EmbeddedCover> extractFlacCover(Path songPath) throws IOException {
        try (InputStream inputStream = Files.newInputStream(songPath)) {
            byte[] signature = inputStream.readNBytes(4);
            if (signature.length < 4 || signature[0] != 'f' || signature[1] != 'L' || signature[2] != 'a' || signature[3] != 'C') {
                return Optional.empty();
            }

            boolean lastBlock = false;
            while (!lastBlock) {
                byte[] blockHeader = inputStream.readNBytes(4);
                if (blockHeader.length < 4) {
                    return Optional.empty();
                }
                lastBlock = (blockHeader[0] & 0x80) != 0;
                int blockType = blockHeader[0] & 0x7F;
                int blockLength = ((blockHeader[1] & 0xFF) << 16) | ((blockHeader[2] & 0xFF) << 8) | (blockHeader[3] & 0xFF);
                byte[] blockData = inputStream.readNBytes(blockLength);
                if (blockData.length < blockLength) {
                    return Optional.empty();
                }
                if (blockType == 6) {
                    return parseFlacPictureBlock(blockData);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<EmbeddedCover> parseFlacPictureBlock(byte[] blockData) throws IOException {
        try (InputStream inputStream = new java.io.ByteArrayInputStream(blockData)) {
            readInt(inputStream);
            int mimeLength = readInt(inputStream);
            String mimeType = new String(inputStream.readNBytes(mimeLength), StandardCharsets.UTF_8);
            int descriptionLength = readInt(inputStream);
            inputStream.readNBytes(descriptionLength);
            readInt(inputStream);
            readInt(inputStream);
            readInt(inputStream);
            readInt(inputStream);
            int pictureDataLength = readInt(inputStream);
            byte[] pictureData = inputStream.readNBytes(pictureDataLength);
            return pictureData.length == 0 ? Optional.empty() : Optional.of(new EmbeddedCover(mimeType, pictureData));
        }
    }

    private Path findSongPath(Long trackId) {
        if (trackId == null) {
            return null;
        }
        Path existing = indexedSongs.get(trackId);
        if (existing != null && Files.exists(existing)) {
            return existing;
        }
        rebuildIndex();
        return indexedSongs.get(trackId);
    }

    private synchronized void rebuildIndex() {
        indexedSongs.clear();
        coverCache.clear();
        if (!Files.isDirectory(songsDirectory)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(songsDirectory, 1)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isSupportedAudioFile)
                    .sorted(Comparator.comparingInt(this::extensionPriority))
                    .forEach(path -> extractTrackId(path.getFileName().toString()).ifPresent(trackId ->
                            indexedSongs.putIfAbsent(trackId, path)));
        } catch (IOException ignored) {
            indexedSongs.clear();
        }
    }

    private boolean isSupportedAudioFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    private int extensionPriority(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".mp3")) return 0;
        if (fileName.endsWith(".m4a")) return 1;
        if (fileName.endsWith(".flac")) return 2;
        return 3;
    }

    private Optional<Long> extractTrackId(String fileName) {
        Matcher matcher = TRACK_ID_PATTERN.matcher(fileName);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private Path resolveSongsDirectory() {
        Path workingDirectory = Paths.get("").toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                workingDirectory.resolve("songs-data"),
                workingDirectory.resolve("../songs-data").normalize(),
                workingDirectory.resolve("../../songs-data").normalize(),
                workingDirectory.resolve("songs"),
                workingDirectory.resolve("../songs").normalize(),
                workingDirectory.resolve("../../songs").normalize()
        );
        return candidates.stream()
                .filter(Files::isDirectory)
                .findFirst()
                .orElse(workingDirectory.resolve("../songs-data").normalize());
    }

    private int syncSafeInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0x7F) << 21)
                | ((bytes[offset + 1] & 0x7F) << 14)
                | ((bytes[offset + 2] & 0x7F) << 7)
                | (bytes[offset + 3] & 0x7F);
    }

    private int bigEndianInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private int indexOfZero(byte[] bytes, int start, int step) {
        for (int i = start; i < bytes.length; i += step) {
            if (bytes[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfDescriptionTerminator(byte[] bytes, int start, int encoding) {
        int step = terminatorLength(encoding);
        if (step == 1) {
            return indexOfZero(bytes, start, 1);
        }
        for (int i = start; i + 1 < bytes.length; i += 2) {
            if (bytes[i] == 0 && bytes[i + 1] == 0) {
                return i;
            }
        }
        return -1;
    }

    private int terminatorLength(int encoding) {
        return (encoding == 1 || encoding == 2) ? 2 : 1;
    }

    private int readInt(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readNBytes(4);
        return ((bytes[0] & 0xFF) << 24)
                | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8)
                | (bytes[3] & 0xFF);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record AudioAsset(String audioUrl, String artworkUrl, boolean localAvailable) {
    }

    public record EmbeddedCover(String contentType, byte[] data) {
    }
}
