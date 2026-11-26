import React, {useMemo} from 'react';

import {ILapDriver} from './interfaces';
import {formatLap, formatSector, lapSeries, sectorRows} from '../../utils/sectors';
import LapChart from '../common/LapChart';

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

interface IProps {
	drivers: Array<ILapDriver>;
	title: string;
}

// Sector times and lap-by-lap pace from the ams2_stats addon's per-lap data
const LapAnalysis = ({drivers, title}: IProps) => {
	const rows = useMemo(() => sectorRows(drivers), [drivers]);
	const {series, min, max} = useMemo(() => lapSeries(drivers), [drivers]);

	if (rows.length === 0 || rows.every(r => r.bestLap === null)) {
		return null;
	}

	// The quickest of each sector across the field is highlighted
	const fastest = (key: 's1' | 's2' | 's3') => Math.min(...rows.map(r => r[key] ?? Infinity));
	const fastestS = {s1: fastest('s1'), s2: fastest('s2'), s3: fastest('s3')};
	const highlight = (key: 's1' | 's2' | 's3', value: number | null) => (value !== null && value === fastestS[key]
		? {fontWeight: 'bold', color: 'secondary.main'} : {});

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

			<LapChart series={series} min={min} max={max} subtitle="Lap times (valid laps; click a name to hide or show it)" />
		</Box>
	);
};

export default LapAnalysis;
