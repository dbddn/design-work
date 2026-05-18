import { useEffect, useMemo, useRef, useState } from 'react';

const formatTime = (seconds) => {
  const safeSeconds = Number.isFinite(Number(seconds)) ? Math.max(0, Number(seconds)) : 0;
  const mins = Math.floor(safeSeconds / 60);
  const secs = Math.floor(safeSeconds % 60);
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
};

const ratingMarks = [1, 2, 3, 4, 5];

const isValidGenre = (value) => {
  const text = String(value || '').trim();
  if (!text) return false;
  const normalized = text.toLowerCase();
  return normalized !== 'unknown' && normalized !== '未分类';
};

export default function PlayerBar({
  track,
  onPrev,
  onNext,
  onSkip,
  onToggleFavorite,
  onOpenDetail,
  onRate,
  isFavorited = false,
  currentRating = 0
}) {
  const audioRef = useRef(null);
  const [expanded, setExpanded] = useState(false);
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

    audio.src = track.audioUrl;
    audio.currentTime = 0;
    if (isPlaying) {
      audio.play().catch(() => setIsPlaying(false));
    }
    return undefined;
  }, [hasAudio, isPlaying, track]);

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

  const title = track?.title || '暂无播放记录';
  const artist = track?.artist || '等待选择歌曲';
  const album = track?.album || '点击首页或搜索结果开始播放';
  const genre = track?.genre || '';
  const description = track?.description || '播放器会展示当前歌曲的歌词与简介。';
  const lyrics =
    Array.isArray(track?.lyrics) && track.lyrics.length > 0 ? track.lyrics : ['暂无歌词内容'];

  return (
    <div className={`player-bar glass-panel ${expanded ? 'expanded' : ''}`}>
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
          onClick={() => setExpanded((value) => !value)}
          aria-label="展开播放器详情"
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
            disabled={!track || !hasAudio}
          />
          <span>{formatTime(duration)}</span>
        </div>

        <div className="player-controls compact">
          <button type="button" className="icon-btn" onClick={onPrev} disabled={!track}>
            上一首
          </button>
          <button type="button" className="icon-btn primary" onClick={handleTogglePlay} disabled={!track || !hasAudio}>
            {isPlaying ? '暂停' : '播放'}
          </button>
          <button type="button" className="icon-btn" onClick={onNext || onSkip} disabled={!track}>
            下一首
          </button>
          <button
            type="button"
            className={`icon-btn ${isFavorited ? 'active' : ''}`}
            onClick={onToggleFavorite}
            disabled={!track}
          >
            {isFavorited ? '已收藏' : '收藏'}
          </button>
          <button type="button" className="icon-btn" onClick={() => track && onOpenDetail?.(track)} disabled={!track}>
            详情
          </button>
        </div>
      </div>

      <div className="player-utility-row">
        <div className="player-rating-group" aria-label="歌曲评分">
          <span className="player-rating-label">评分</span>
          <div className="player-rating-stars">
            {ratingMarks.map((score) => (
              <button
                key={score}
                type="button"
                className={`rating-star ${currentRating >= score ? 'active' : ''}`}
                onClick={() => onRate?.(score)}
                disabled={!track}
                aria-label={`评 ${score} 分`}
              >
                ★
              </button>
            ))}
          </div>
        </div>
        {!hasAudio && track ? <span className="chip soft">当前歌曲暂无可用音源</span> : null}
        <button
          type="button"
          className="player-expand-trigger"
          onClick={() => setExpanded((value) => !value)}
          disabled={!track}
        >
          {expanded ? '收起歌词与信息' : '展开歌词与信息'}
        </button>
      </div>

      {expanded ? (
        <div className="player-details">
          <div className="detail-card">
            <span className="eyebrow">歌曲信息</span>
            <h4>{album}</h4>
            <div className="chips">
              {isValidGenre(genre) ? <span className="genre-pill">{genre}</span> : null}
              <span className="chip soft">{artist}</span>
            </div>
            <p className="detail-copy">{description}</p>
            <button type="button" className="btn subtle small" onClick={() => track && onOpenDetail?.(track)}>
              打开歌曲详情页
            </button>
          </div>
          <div className="detail-card">
            <span className="eyebrow">歌词</span>
            <div className="lyrics-block">
              {lyrics.map((line, index) => (
                <p key={`${line}-${index}`}>{line}</p>
              ))}
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
