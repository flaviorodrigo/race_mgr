import React, {useMemo, useState} from 'react';
import {useTheme} from '@mui/material/styles';

import {ILapDriver} from './interfaces';
import {formatLap, formatSector, lapSeries, sectorRows} from '../../utils/sectors';

import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Paper from '@mui/material/Paper';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';

const COLORS = ['#e53935', '#1e88e5', '#43a047', '#fb8c00', '#8e24aa', '#00acc1', '#6d4c41', '#d81b60', '#7cb342', '#3949ab', '#f4511e', '#00897b'];

interface IProps {
	drivers: Array<ILapDriver>;
	title: string;
}

// Sector times and lap-by-lap pace from the ams2_stats addon's per-lap data
const LapAnalysis = ({drivers, title}: IProps) => {
	const theme = useTheme();
	const rows = useMemo(() => sectorRows(drivers), [drivers]);
	const {series, min, max} = useMemo(() => lapSeries(drivers), [drivers]);
	const [hidden, setHidden] = useState<Array<string>>([]);

	if (rows.length === 0 || rows.every(r => r.bestLap === null)) {
		return null;
	}

	// The quickest of each sector across the field is highlighted
	const fastest = (key: 's1' | 's2' | 's3') => Math.min(...rows.map(r => r[key] ?? Infinity));
	const fastestS = {s1: fastest('s1'), s2: fastest('s2'), s3: fastest('s3')};
	const highlight = (key: 's1' | 's2' | 's3', value: number | null) => (value !== null && value === fastestS[key]
		? {fontWeight: 'bold', color: 'secondary.main'} : {});

	// Chart geometry (a plain SVG: no chart library needed)
	const width = 760;
	const height = 280;
	const pad = {left: 64, right: 12, top: 12, bottom: 28};
	const laps = series.flatMap(s => s.points.map(p => p.lap));
	const minLap = Math.min(...laps);
	const maxLap = Math.max(...laps);
	const x = (lap: number) => pad.left + (((lap - minLap) / Math.max(1, maxLap - minLap)) * (width - pad.left - pad.right));
	const span = Math.max(1, max - min);
	const y = (time: number) => pad.top + (((time - min) / span) * (height - pad.top - pad.bottom));
	const ticks = [0, 0.25, 0.5, 0.75, 1].map(f => min + (span * f));
	const toggle = (name: string) => setHidden(hidden.includes(name) ? hidden.filter(h => h !== name) : [...hidden, name]);

	return (
		<Box sx={{mb: 3}}>
			<Typography variant="h6">{title}</Typography>
			<TableContainer component={Paper} variant="outlined" sx={{mb: 2}}>
				<Table size="small" aria-label={`${title} sector times`}>
					<TableHead>
						<TableRow>
							<TableCell>Driver</TableCell>
							<TableCell align="right">Best lap</TableCell>
							<TableCell align="right">S1</TableCell>
							<TableCell align="right">S2</TableCell>
							<TableCell align="right">S3</TableCell>
							<TableCell align="right">Ideal lap</TableCell>
							<TableCell align="right">Left on the table</TableCell>
						</TableRow>
					</TableHead>
					<TableBody>
						{rows.map(r => (
							<TableRow key={r.name} hover>
								<TableCell component="th" scope="row">
									{r.name}
									{r.state && r.state !== 'Finished' && <Chip size="small" label={r.state === 'Disqualified' ? 'DSQ' : r.state === 'Retired' ? 'DNF' : r.state} sx={{ml: 1}} />}
								</TableCell>
								<TableCell align="right">{formatLap(r.bestLap)}</TableCell>
								<TableCell align="right" sx={highlight('s1', r.s1)}>{formatSector(r.s1)}</TableCell>
								<TableCell align="right" sx={highlight('s2', r.s2)}>{formatSector(r.s2)}</TableCell>
								<TableCell align="right" sx={highlight('s3', r.s3)}>{formatSector(r.s3)}</TableCell>
								<TableCell align="right">{formatLap(r.theoretical)}</TableCell>
								<TableCell align="right">{r.gap === null ? '' : `${(r.gap / 1000).toFixed(3)} s`}</TableCell>
							</TableRow>
						))}
					</TableBody>
				</Table>
			</TableContainer>

			{series.length > 0 && <>
				<Typography variant="subtitle2" sx={{mb: 1}}>Lap times (valid laps; click a name to hide or show it)</Typography>
				<Box sx={{overflowX: 'auto'}}>
					<svg role="img" aria-label="Lap times by lap" viewBox={`0 0 ${width} ${height}`} style={{width: '100%', minWidth: 420, maxHeight: 320}}>
						{ticks.map(t => (
							<g key={t}>
								<line x1={pad.left} x2={width - pad.right} y1={y(t)} y2={y(t)} stroke={theme.palette.divider} />
								<text x={pad.left - 6} y={y(t) + 4} fontSize="11" textAnchor="end" fill={theme.palette.text.secondary}>{formatLap(Math.round(t))}</text>
							</g>
						))}
						{[minLap, maxLap].map(l => (
							<text key={l} x={x(l)} y={height - 8} fontSize="11" textAnchor="middle" fill={theme.palette.text.secondary}>{`Lap ${l}`}</text>
						))}
						{series.filter(s => !hidden.includes(s.name)).map(s => {
							const color = COLORS[series.findIndex(o => o.name === s.name) % COLORS.length];
							return (
								<polyline key={s.name} fill="none" stroke={color} strokeWidth="2" strokeLinejoin="round"
									points={s.points.map(p => `${x(p.lap).toFixed(1)},${y(p.time).toFixed(1)}`).join(' ')} />
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
			</>}
		</Box>
	);
};

export default LapAnalysis;
