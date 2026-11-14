import React from 'react';

interface SpeedArcGaugeProps {
  speedMb: number;
  maxSpeedMb?: number;
  isActive?: boolean;
}

export const SpeedArcGauge: React.FC<SpeedArcGaugeProps> = ({
  speedMb,
  maxSpeedMb = 25,
  isActive = true,
}) => {
  const safeSpeed = Number.isFinite(speedMb) && speedMb > 0 ? speedMb : 0;
  const ratio = Math.min(1, Math.max(0, safeSpeed / maxSpeedMb));

  // 220-degree speedometer arc from 160deg to 380deg
  const size = 68;
  const cx = size / 2;
  const cy = size / 2 + 4;
  const r = 24;
  const startAngle = 160;
  const sweepAngle = 220;
  const endAngle = startAngle + sweepAngle;

  const polarToCartesian = (angleDeg: number) => {
    const rad = (angleDeg * Math.PI) / 180;
    return {
      x: cx + r * Math.cos(rad),
      y: cy + r * Math.sin(rad),
    };
  };

  const describeArc = (startDeg: number, finishDeg: number) => {
    const start = polarToCartesian(startDeg);
    const end = polarToCartesian(finishDeg);
    const largeArcFlag = finishDeg - startDeg <= 180 ? '0' : '1';
    return `M ${start.x.toFixed(2)} ${start.y.toFixed(2)} A ${r} ${r} 0 ${largeArcFlag} 1 ${end.x.toFixed(2)} ${end.y.toFixed(2)}`;
  };

  const trackPath = describeArc(startAngle, endAngle);
  const activeAngle = startAngle + sweepAngle * (isActive ? Math.max(0.03, ratio) : 0);
  const activePath = isActive && safeSpeed > 0 ? describeArc(startAngle, activeAngle) : '';
  const tipPoint = polarToCartesian(activeAngle);

  const formattedSpeed =
    safeSpeed >= 10
      ? safeSpeed.toFixed(1)
      : safeSpeed > 0
      ? safeSpeed.toFixed(1)
      : '0.0';

  return (
    <div className="flex flex-col items-center justify-center px-2 py-1 rounded-lg bg-slate-950 border border-accent shrink-0 w-[86px]">
      <svg width={size} height={46} viewBox={`0 0 ${size} 50`} className="overflow-visible">
        <path
          d={trackPath}
          fill="none"
          stroke="rgba(30, 41, 59, 0.9)"
          strokeWidth={5}
          strokeLinecap="round"
        />
        {activePath && (
          <path
            d={activePath}
            fill="none"
            stroke="var(--od-accent)"
            strokeWidth={5}
            strokeLinecap="round"
            style={{ filter: 'drop-shadow(0 0 4px rgba(var(--od-accent-rgb), 0.75))' }}
          />
        )}
        {isActive && (
          <circle
            cx={tipPoint.x}
            cy={tipPoint.y}
            r={2.8}
            fill={safeSpeed > 0 ? '#ffffff' : 'rgba(var(--od-accent-rgb), 0.6)'}
          />
        )}
      </svg>
      <div className="font-mono text-[10px] font-bold text-accent-soft tabular-nums whitespace-nowrap -mt-2">
        {formattedSpeed} <span className="text-[9px] text-slate-400">MB/s</span>
      </div>
    </div>
  );
};
