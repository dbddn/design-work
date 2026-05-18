import { useMemo } from 'react';

export default function TimelineStrip({ items = [], activeKey, onSelect }) {
  const activeIndex = useMemo(() => {
    if (!Array.isArray(items) || items.length === 0) return 0;
    const index = items.findIndex((item) => (item.period || item.year) === activeKey);
    return index >= 0 ? index : 0;
  }, [items, activeKey]);

  const activeItem = items[activeIndex] || null;
  const ratio = items.length > 1 ? activeIndex / (items.length - 1) : 0;
  const labelStyle = useMemo(() => {
    if (ratio <= 0.08) {
      return { left: '0%', transform: 'translateX(0)' };
    }
    if (ratio >= 0.92) {
      return { left: '100%', transform: 'translateX(-100%)' };
    }
    return { left: `${ratio * 100}%`, transform: 'translateX(-50%)' };
  }, [ratio]);

  const selectByIndex = (index) => {
    const safeIndex = Math.max(0, Math.min(index, items.length - 1));
    const target = items[safeIndex];
    if (target) onSelect?.(target);
  };

  return (
    <div className="timeline-slider-v2">
      <div className="timeline-axis-wrap">
        <div className="timeline-thumb-label" style={labelStyle}>
          <strong>{activeItem?.period || activeItem?.year || '未知区间'}</strong>
        </div>
        <input
          type="range"
          min={0}
          max={Math.max(0, items.length - 1)}
          step={1}
          value={activeIndex}
          onChange={(event) => selectByIndex(Number(event.target.value))}
          className="timeline-axis-range"
          aria-label="时间轴选择"
        />
      </div>
    </div>
  );
}
