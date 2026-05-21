import { useEffect, useMemo, useRef, useState } from 'react';

const formatTime = (seconds) => {
  const safeSeconds = Number.isFinite(Number(seconds)) ? Math.max(0, Number(seconds)) : 0;
  const mins = Math.floor(safeSeconds / 60);
  const secs = Math.floor(safeSeconds % 60);
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
};

export default function PlayerBar({
  track,
  onPrev,
  onNext,
  onSkip,
  onToggleFavorite,
  onOpenDetail,
  isFavorited = false
}) {
  const audioRef = useRef(null);
  const [isPlaying, setIsPlaying] = useState(Boolean(track));
  const [progress, setProgress] = useState(0);
  const [coverFailed, setCoverFailed] = useState(false);
  const [resolvedDuration, setResolvedDuration] = useState(0);

  const hasAudio = Boolean(track?.audioUrl);
  const duration = useMemo(() => {
    const value = Number(resolvedDuration || track?.duration || track?.durationSec);
    return Number.isFinite(value) && value > 0 ? value : 214;
  }, [resolvedDuration, track]);

  useEffect(() => {
    setProgress(0);
    setIsPlaying(Boolean(track) && Boolean(track?.audioUrl));
    setCoverFailed(false);
    setResolvedDuration(Number(track?.duration || track?.durationSec) || 0);
  }, [track]);

  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return undefined;

    if (!hasAudio || !track?.audioUrl) {
      audio.pause();
      audio.removeAttribute('src');
      audio.load();
      setIsPlaying(false);
      return undefined;
    }

    if (audio.getAttribute('src') !== track.audioUrl) {
      audio.src = track.audioUrl;
      audio.currentTime = 0;
      audio.load();
    }
    audio.play().catch(() => setIsPlaying(false));
    return undefined;
  }, [hasAudio, track?.audioUrl]);

  const handleTogglePlay = () => {
    if (!track || !hasAudio) {
      setIsPlaying(false);
      return;
    }

    const nextPlaying = !isPlaying;
    setIsPlaying(nextPlaying);

    const audio = audioRef.current;
    if (!audio) return;
    if (nextPlaying) {
      audio.play().catch(() => setIsPlaying(false));
    } else {
      audio.pause();
    }
  };

  const handleSeek = (nextValue) => {
    const safeValue = Number(nextValue);
    if (!Number.isFinite(safeValue)) return;
    setProgress(safeValue);
    if (hasAudio && audioRef.current) {
      audioRef.current.currentTime = safeValue;
    }
  };

  if (!track) {
    return <div className="player-hover-zone" aria-hidden="true" />;
  }

  const title = track?.title || '暂无播放';
  const artist = track?.artist || '等待选择歌曲';
  const album = track?.album || '从首页或搜索结果开始播放';

  return (
    <>
      <div className="player-hover-zone" aria-hidden="true" />
      <div className="player-bar glass-panel">
        <audio
          ref={audioRef}
          preload="metadata"
          onLoadedMetadata={(event) => {
            const nextDuration = Number(event.currentTarget.duration);
            if (Number.isFinite(nextDuration) && nextDuration > 0) {
              setResolvedDuration(nextDuration);
              setProgress(0);
            }
          }}
          onTimeUpdate={(event) => setProgress(event.currentTarget.currentTime || 0)}
          onEnded={() => {
            setIsPlaying(false);
            onNext?.();
          }}
        />

        <div className="player-main compact">
          <button
            type="button"
            className="player-cover compact"
            onClick={() => onOpenDetail?.(track)}
            aria-label="打开歌曲详情"
          >
            {track?.artworkUrl && !coverFailed ? (
              <img src={track.artworkUrl} alt={`${title} 封面`} onError={() => setCoverFailed(true)} />
            ) : (
              <span
                className="player-cover-fill"
                style={{
                  background:
                    track?.artwork ||
                    'linear-gradient(135deg, rgba(0,122,255,0.94), rgba(88,182,255,0.82) 55%, rgba(255,255,255,0.95))'
                }}
              />
            )}
            <span className="player-cover-shade" />
          </button>

          <div className="player-meta compact">
            <strong>{title}</strong>
            <p>{artist}</p>
            <span className="player-meta-subtle">{album}</span>
          </div>

          <div className="player-progress compact">
            <span>{formatTime(progress)}</span>
            <input
              type="range"
              min="0"
              max={Math.max(duration, progress, 1)}
              value={Math.min(progress, Math.max(duration, progress, 1))}
              onChange={(event) => handleSeek(event.target.value)}
              disabled={!hasAudio}
            />
            <span>{formatTime(duration)}</span>
          </div>

          <div className="player-controls compact">
            <button type="button" className="icon-btn" onClick={onPrev} aria-label="上一首">
              上一首
            </button>
            <button type="button" className="icon-btn primary" onClick={handleTogglePlay} disabled={!hasAudio}>
              {isPlaying ? '暂停' : '播放'}
            </button>
            <button type="button" className="icon-btn" onClick={onNext || onSkip} aria-label="下一首">
              下一首
            </button>
            <button type="button" className={`icon-btn ${isFavorited ? 'active' : ''}`} onClick={onToggleFavorite}>
              {isFavorited ? '已收藏' : '收藏'}
            </button>
            <button type="button" className="icon-btn" onClick={() => onOpenDetail?.(track)}>
              详情
            </button>
            {!hasAudio ? <span className="chip soft">暂无音源</span> : null}
          </div>
        </div>
      </div>
    </>
  );
}
