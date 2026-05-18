import { toArray } from './view';

export const dayparts = [
  {
    key: 'morning',
    label: '上午',
    mood: '清新舒展',
    description: '适合刚开始一天时播放，节奏轻盈，氛围明亮。'
  },
  {
    key: 'afternoon',
    label: '下午',
    mood: '明亮活力',
    description: '适合专注工作或学习时保持状态，旋律流畅，也更有推进感。'
  },
  {
    key: 'evening',
    label: '晚上',
    mood: '夜色渐起',
    description: '适合傍晚和晚间通勤，层次更丰富，也更有故事感。'
  },
  {
    key: 'midnight',
    label: '深夜',
    mood: '安静沉浸',
    description: '适合夜深人静时慢慢聆听，情绪更细腻，也更容易沉浸。'
  }
];

export const getDaypartMeta = (key) => dayparts.find((item) => item.key === key) || dayparts[0];

export const buildDaypartGroups = (tracks, tracksPerGroup = 3) => {
  const safeTracks = toArray(tracks);
  const perGroup = Math.max(1, Number(tracksPerGroup) || 3);

  return dayparts.map((part, index) => ({
    ...part,
    tracks: safeTracks.slice(index * perGroup, index * perGroup + perGroup)
  }));
};
