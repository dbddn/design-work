import { useEffect, useState } from 'react';
import EChartCard from '../components/EChartCard';
import { analyticsApi } from '../api/services';
import { toGenrePieOption, toHeatmapOption } from '../utils/chartTransform';
import { getErrorMessage, toArray } from '../utils/view';

export default function AnalyticsPage() {
  const [heatmap, setHeatmap] = useState([]);
  const [genres, setGenres] = useState([]);
  const [error, setError] = useState('');

  useEffect(() => {
    const run = async () => {
      setError('');
      try {
        const [heatmapData, genreData] = await Promise.all([
          analyticsApi.heatmap(30),
          analyticsApi.genres(30)
        ]);
        setHeatmap(toArray(heatmapData));
        setGenres(toArray(genreData));
      } catch (requestError) {
        setHeatmap([]);
        setGenres([]);
        setError(getErrorMessage(requestError, '数据分析加载失败，请稍后重试'));
      }
    };
    run();
  }, []);

  return (
    <div className="page">
      <section className="glass-card">
        <div className="section-heading">
          <div>
            <span className="eyebrow">私人雷达</span>
            <h2>用蓝白色系重构数据分析，让图表更轻、更稳定</h2>
          </div>
          <p>近 30 天热力图和流派分布会在数据缺失或请求失败时自动回退，不会打断页面展示。</p>
        </div>
        {error ? <p>{error}</p> : null}
      </section>
      <div className="dashboard-grid">
        <EChartCard
          title="近 30 天播放热力图"
          subtitle="用更简洁的发光色块展示每日播放强度。"
          option={toHeatmapOption(heatmap)}
        />
        <EChartCard
          title="流派分布"
          subtitle="蓝、白、浅青渐变替代厚重边框，让图表更贴合整体界面。"
          option={toGenrePieOption(genres)}
        />
      </div>
    </div>
  );
}
