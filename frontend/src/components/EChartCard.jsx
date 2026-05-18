import { useEffect, useRef } from 'react';
import * as echarts from 'echarts';

export default function EChartCard({ title, subtitle, option, height = 320 }) {
  const ref = useRef(null);

  useEffect(() => {
    if (!ref.current) return undefined;
    const chart = echarts.init(ref.current);
    chart.setOption(option || {}, true);
    const resize = () => chart.resize();
    window.addEventListener('resize', resize);
    return () => {
      window.removeEventListener('resize', resize);
      chart.dispose();
    };
  }, [option]);

  return (
    <section className="glass-card chart-card">
      <div className="section-heading">
        <div>
          <span className="eyebrow">音乐洞察</span>
          <h3>{title}</h3>
        </div>
        {subtitle ? <p>{subtitle}</p> : null}
      </div>
      <div ref={ref} style={{ height, minHeight: height }} />
    </section>
  );
}
