import { useMemo, useState } from 'react';
import { assistantApi } from '../api/services';
import { getErrorMessage, toArray } from '../utils/view';

const quickPrompts = [
  '给我一份适合深夜写代码、偏安静和怀旧的歌单',
  '想听适合通勤路上的轻快中文歌',
  '帮我做一份适合下雨天独处的推荐歌单'
];

export default function AssistantWidget({ onPlay, onOpenDetail }) {
  const [open, setOpen] = useState(false);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [messages, setMessages] = useState([
    {
      role: 'assistant',
      content: '你好，我是你的智能选歌助手。你可以直接告诉我想听的场景、情绪或风格，我会结合系统候选歌曲帮你生成一份可播放的歌单。'
    }
  ]);
  const [playlist, setPlaylist] = useState([]);
  const [playlistTitle, setPlaylistTitle] = useState('');
  const [playlistSummary, setPlaylistSummary] = useState('');
  const [reasoningSummary, setReasoningSummary] = useState([]);
  const [modelName, setModelName] = useState('');
  const [usedFallback, setUsedFallback] = useState(false);

  const historyPayload = useMemo(
    () =>
      messages
        .slice(-6)
        .map((item) => ({ role: item.role, content: item.content }))
        .filter((item) => item.content && item.content.trim()),
    [messages]
  );

  const submit = async (presetMessage) => {
    const message = (presetMessage ?? input).trim();
    if (!message || loading) return;

    const nextMessages = [...messages, { role: 'user', content: message }];
    setMessages(nextMessages);
    setInput('');
    setLoading(true);
    setError('');

    try {
      const data = await assistantApi.chat({
        message,
        history: historyPayload
      });
      setMessages((current) => [
        ...current,
        {
          role: 'assistant',
          content: data?.reply || '我已经先为你整理好一份推荐歌单。'
        }
      ]);
      setPlaylistTitle(data?.playlistTitle || '推荐歌单');
      setPlaylistSummary(data?.playlistSummary || '');
      setPlaylist(toArray(data?.playlist));
      setReasoningSummary(toArray(data?.reasoningSummary));
      setModelName(data?.model || '');
      setUsedFallback(Boolean(data?.usedFallback));
    } catch (requestError) {
      setError(getErrorMessage(requestError, '智能助手暂时不可用，请稍后重试'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <button
        type="button"
        className="assistant-fab"
        aria-label="打开智能推荐助手"
        onClick={() => setOpen((value) => !value)}
      >
        AI
      </button>

      {open ? (
        <section className="assistant-panel glass-panel">
          <div className="assistant-panel-glow assistant-panel-glow-a" />
          <div className="assistant-panel-glow assistant-panel-glow-b" />

          <div className="assistant-head">
            <div className="assistant-head-copy">
              <span className="eyebrow">智能推荐助手</span>
              <strong>把你的需求变成一份可直接播放的歌单</strong>
              <p>基于当前候选歌曲、用户偏好和场景信息，为你生成更贴近当下状态的播放建议。</p>
            </div>
            <button type="button" className="btn subtle assistant-close-btn" onClick={() => setOpen(false)}>
              收起
            </button>
          </div>

          <div className="assistant-status-row">
            <span className="assistant-status-pill primary">{modelName || 'qwen3.5-35b-a3b'}</span>
            <span className={`assistant-status-pill ${usedFallback ? 'warn' : 'success'}`}>
              {usedFallback ? '系统兜底' : '模型直连'}
            </span>
          </div>

          <div className="assistant-quick-prompts">
            {quickPrompts.map((prompt) => (
              <button
                key={prompt}
                type="button"
                className="assistant-quick-chip"
                onClick={() => submit(prompt)}
                disabled={loading}
              >
                {prompt}
              </button>
            ))}
          </div>

          <div className="assistant-messages">
            {messages.map((item, index) => (
              <div key={`${item.role}-${index}`} className={`assistant-bubble ${item.role === 'user' ? 'user' : 'assistant'}`}>
                {item.content}
              </div>
            ))}
            {loading ? <div className="assistant-bubble assistant">正在整理你的歌单和推荐理由...</div> : null}
          </div>

          {reasoningSummary.length > 0 ? (
            <div className="assistant-reasoning-card">
              <div className="assistant-section-head">
                <strong>本次思考摘要</strong>
                <span>模型筛选逻辑</span>
              </div>
              <div className="assistant-reasoning-list">
                {reasoningSummary.slice(0, 3).map((item, index) => (
                  <p key={`${item}-${index}`}>{item}</p>
                ))}
              </div>
            </div>
          ) : null}

          {playlist.length > 0 ? (
            <div className="assistant-playlist">
              <div className="assistant-playlist-head">
                <div>
                  <strong>{playlistTitle || '为你生成的推荐歌单'}</strong>
                  {playlistSummary ? <p>{playlistSummary}</p> : null}
                </div>
              </div>
              <div className="assistant-playlist-list">
                {playlist.map((item, index) => (
                  <article className="assistant-track-card" key={`${item?.track?.id || index}-${index}`}>
                    <div className="assistant-track-main">
                      <div className="assistant-track-rank">#{index + 1}</div>
                      <div className="assistant-track-copy">
                        <strong>{item?.track?.title || '未命名歌曲'}</strong>
                        <p>{item?.track?.artist || '未知歌手'}</p>
                        <small>{item?.reason || '这首歌和你当前的需求比较贴合。'}</small>
                      </div>
                    </div>
                    <div className="assistant-track-actions">
                      <button type="button" className="btn subtle small" onClick={() => onPlay?.(item?.track)}>
                        播放
                      </button>
                      <button type="button" className="btn subtle small" onClick={() => onOpenDetail?.(item?.track)}>
                        详情
                      </button>
                    </div>
                  </article>
                ))}
              </div>
            </div>
          ) : null}

          {error ? <p className="error">{error}</p> : null}

          <div className="assistant-input">
            <textarea
              value={input}
              onChange={(event) => setInput(event.target.value)}
              placeholder="例如：给我一份适合深夜写代码、偏宁静和怀旧的中文歌单"
              rows={4}
            />
            <div className="assistant-input-actions">
              <span>描述越具体，生成的歌单越贴近你的场景。</span>
              <button type="button" className="btn" onClick={() => submit()} disabled={loading}>
                {loading ? '生成中...' : '生成歌单'}
              </button>
            </div>
          </div>
        </section>
      ) : null}
    </>
  );
}
