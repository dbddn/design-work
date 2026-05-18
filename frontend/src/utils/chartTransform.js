const textColor = '#1D1D1F';
const subTextColor = '#86868B';
const lineColor = 'rgba(0, 122, 255, 0.12)';
const gradientBlue = ['#007AFF', '#57A6FF', '#9FD0FF', '#CFE7FF', '#D7F5F2'];

export const toHeatmapOption = (points = []) => {
  const sorted = [...points].reverse();
  const values = sorted.map((d) => Number(d?.count || 0));
  const max = Math.max(...values, 12);

  return {
    animationDuration: 900,
    animationEasing: 'cubicOut',
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(255,255,255,0.92)',
      borderColor: 'rgba(0,122,255,0.12)',
      textStyle: { color: textColor },
      formatter: (params) => `${sorted[params?.data?.[0]]?.date || '未知日期'}<br/>播放次数：${params?.data?.[2] || 0}`
    },
    grid: { top: 18, left: 8, right: 8, bottom: 28, containLabel: true },
    xAxis: {
      type: 'category',
      data: sorted.map((d) => (d?.date ? d.date.slice(5) : '--')),
      axisTick: { show: false },
      axisLine: { show: false },
      axisLabel: { color: subTextColor, margin: 12 }
    },
    yAxis: {
      type: 'category',
      data: ['播放'],
      axisTick: { show: false },
      axisLine: { show: false },
      axisLabel: { color: subTextColor }
    },
    visualMap: {
      min: 0,
      max,
      show: false,
      inRange: {
        color: ['#EDF5FF', '#C8E1FF', '#7DB8FF', '#007AFF']
      }
    },
    series: [
      {
        type: 'heatmap',
        data: sorted.map((d, index) => [index, 0, Number(d?.count || 0)]),
        itemStyle: {
          borderRadius: 10,
          borderColor: '#F5F7FA',
          borderWidth: 6
        },
        emphasis: {
          itemStyle: {
            shadowBlur: 18,
            shadowColor: 'rgba(0, 122, 255, 0.16)'
          }
        }
      }
    ]
  };
};

export const toGenrePieOption = (genres = []) => ({
  animationDuration: 900,
  tooltip: {
    trigger: 'item',
    backgroundColor: 'rgba(255,255,255,0.92)',
    borderColor: 'rgba(0,122,255,0.12)',
    textStyle: { color: textColor },
    formatter: '{b}<br/>占比：{d}%'
  },
  legend: {
    bottom: 0,
    icon: 'circle',
    textStyle: { color: subTextColor }
  },
  series: [
    {
      type: 'pie',
      radius: ['45%', '72%'],
      center: ['50%', '46%'],
      avoidLabelOverlap: true,
      itemStyle: {
        borderRadius: 14,
        borderColor: '#FFFFFF',
        borderWidth: 4
      },
      label: {
        color: textColor,
        formatter: '{b}\n{d}%'
      },
      labelLine: {
        lineStyle: { color: lineColor }
      },
      data: genres.map((genre, index) => ({
        value: Number(genre?.count || 0),
        name: genre?.genre || `流派 ${index + 1}`,
        itemStyle: { color: gradientBlue[index % gradientBlue.length] }
      }))
    }
  ]
});

const clamp = (value, min, max) => Math.min(max, Math.max(min, value));

export const toRadarOption = (stats) => {
  const playCount7d = Number(stats?.playCount7d || 0);
  const playCount30d = Number(stats?.playCount30d || 0);
  const likeCount30d = Number(stats?.likeCount30d || 0);
  const skipRate30d = Number(stats?.skipRate30d || 0);
  const weeklyActiveRatio = playCount7d / Math.max(1, playCount30d);

  // Normalize dimensions to avoid extreme distortion and keep the radar comparable across users.
  const tasteStability = clamp(35 + likeCount30d * 2.1 - skipRate30d * 28, 20, 95);
  const focusTendency = clamp(22 + weeklyActiveRatio * 110 - skipRate30d * 12, 15, 95);
  const exploreDesire = clamp(28 + Math.sqrt(playCount30d) * 8 + skipRate30d * 18, 15, 95);
  const replayLoyalty = clamp(26 + likeCount30d * 2.4 - skipRate30d * 34, 10, 95);
  const nightAtmosphere = clamp(20 + Math.min(40, playCount30d * 0.85), 10, 90);

  return ({
  animationDuration: 900,
  tooltip: {
    trigger: 'item',
    backgroundColor: 'rgba(255,255,255,0.92)',
    borderColor: 'rgba(0,122,255,0.12)',
    textStyle: { color: textColor }
  },
  radar: {
    center: ['50%', '50%'],
    radius: '68%',
    splitNumber: 4,
    axisName: { color: subTextColor, fontSize: 12 },
    splitLine: { lineStyle: { color: lineColor } },
    splitArea: { areaStyle: { color: ['rgba(229,240,255,0.16)', 'rgba(229,240,255,0.32)'] } },
    axisLine: { lineStyle: { color: 'rgba(0,122,255,0.1)' } },
    indicator: [
      { name: '品味稳定', max: 100 },
      { name: '专注倾向', max: 100 },
      { name: '探索欲望', max: 100 },
      { name: '复听忠诚', max: 100 },
      { name: '深夜氛围', max: 100 }
    ]
  },
  series: [
    {
      type: 'radar',
      symbol: 'circle',
      symbolSize: 8,
      lineStyle: { width: 2, color: '#007AFF' },
      areaStyle: { color: 'rgba(0,122,255,0.22)' },
      itemStyle: { color: '#57A6FF' },
      data: [
        {
          value: [
            tasteStability,
            focusTendency,
            exploreDesire,
            replayLoyalty,
            nightAtmosphere
          ]
        }
      ]
    }
  ]
  });
};
