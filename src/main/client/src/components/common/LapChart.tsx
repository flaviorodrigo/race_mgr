import React, {useState} from 'react';
import {useTheme} from '@mui/material/styles';

import {formatLap, ISeries} from '../../utils/sectors';

import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Typography from '@mui/material/Typography';

const COLORS = ['#e53935', '#1e88e5', '#43a047', '#fb8c00', '#8e24aa', '#00acc1', '#6d4c41', '#d81b60', '#7cb342', '#3949ab', '#f4511e', '#00897b'];

interface IProps {
	series: Array<ISeries>;
	min: number;
	max: number;
	subtitle?: string;
}

// Shared SVG lap-time evolution chart with clickable driver toggles
const LapChart = ({series, min, max, subtitle}: IProps) => {
	const theme = useTheme();
	const [hidden, setHidden] = useState<Array<string>>([]);

	if (series.length === 0) {
		return null;
	}

	const width = 760;
	const height = 280;
	const pad = {left: 64, right: 12, top: 12, bottom: 28};
	const laps = series.flatMap(s => s.points.map(p => p.lap));
	const minLap = Math.min(...laps);
	const maxLap = Math.max(...laps);
	const x = (lap: number) => (minLap === maxLap
		? (pad.left + width - pad.right) / 2
		: pad.left + (((lap - minLap) / (maxLap - minLap)) * (width - pad.left - pad.right)));
	const span = Math.max(1, max - min);
	const y = (time: number) => pad.top + (((time - min) / span) * (height - pad.top - pad.bottom));
	const ticks = [0, 0.25, 0.5, 0.75, 1].map(f => min + (span * f));
	const lapLabels = minLap === maxLap ? [minLap] : [minLap, maxLap];
	const toggle = (name: string) => setHidden(hidden.includes(name) ? hidden.filter(h => h !== name) : [...hidden, name]);

	return (
		<>
			{subtitle && <Typography variant="subtitle2" sx={{mb: 1}}>{subtitle}</Typography>}
			<Box sx={{overflowX: 'auto'}}>
				<svg role="img" aria-label="Lap times by lap" viewBox={`0 0 ${width} ${height}`} style={{width: '100%', minWidth: 420, maxHeight: 320}}>
					{ticks.map(t => (
						<g key={t}>
							<line x1={pad.left} x2={width - pad.right} y1={y(t)} y2={y(t)} stroke={theme.palette.divider} />
							<text x={pad.left - 6} y={y(t) + 4} fontSize="11" textAnchor="end" fill={theme.palette.text.secondary}>{formatLap(Math.round(t))}</text>
						</g>
					))}
					{lapLabels.map(l => (
						<text key={l} x={x(l)} y={height - 8} fontSize="11" textAnchor="middle" fill={theme.palette.text.secondary}>{`Lap ${l}`}</text>
					))}
					{series.filter(s => !hidden.includes(s.name)).map(s => {
						const color = COLORS[series.findIndex(o => o.name === s.name) % COLORS.length];
						return (
							<g key={s.name}>
								{s.points.length > 1 && (
									<polyline fill="none" stroke={color} strokeWidth="2" strokeLinejoin="round"
										points={s.points.map(p => `${x(p.lap).toFixed(1)},${y(p.time).toFixed(1)}`).join(' ')} />
								)}
								{s.points.map(p => (
									<circle key={p.lap} cx={x(p.lap)} cy={y(p.time)} r="3.5" fill={color}>
										<title>{`${s.name} · Lap ${p.lap}: ${formatLap(p.time)}`}</title>
									</circle>
								))}
							</g>
						);
					})}
				</svg>
			</Box>
			<Box sx={{display: 'flex', flexWrap: 'wrap', gap: 1, mt: 1}}>
				{series.map((s, i) => (
					<Chip key={s.name} size="small" label={s.name} onClick={() => toggle(s.name)} variant={hidden.includes(s.name) ? 'outlined' : 'filled'}
						sx={{bgcolor: hidden.includes(s.name) ? undefined : COLORS[i % COLORS.length], color: hidden.includes(s.name) ? undefined : '#fff'}} />
				))}
			</Box>
		</>
	);
};

export default LapChart;
