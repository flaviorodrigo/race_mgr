import React, {useEffect, useMemo, useState} from 'react';

import RaceMgrApi from '../../api/RaceMgrApi';
import {usePolling} from '../../utils/usePolling';
import {formatLap, formatSector, lapSeries} from '../../utils/sectors';
import {fetchTrackData} from '../Leaderboard/utilities';
import {prettyStage} from './liveApi';
import {EMPTY_LIVE_LAP_HISTORY, toLapDrivers, updateLiveLapHistory} from './liveLaps';
import LapChart from '../common/LapChart';
import PositionBadge from '../common/PositionBadge';
import {TIMING_PURPLE} from '../../skin/colors';
import {ITrackData} from '../../interfaces/asm2Interfaces';

import Alert from '@mui/material/Alert';
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

export interface ITimingCar {
	position?: number,
	name: string,
	player: boolean,
	lap?: number,
	sector?: number,
	s1?: number,
	s2?: number,
	s3?: number,
	lastLap?: number,
	bestLap?: number,
	state?: string,
	vehicleId?: number,
}

export interface ITimingBoard {
	stage?: string,
	trackId?: string,
	raceLength?: number,
	cars: Array<ITimingCar>,
}

// Resolves to null when the game server can't be reached
const fetchTiming = async (): Promise<ITimingBoard | null> => {
	const rmApi = new RaceMgrApi();
	try {
		const response = await rmApi.makeApiCall({url: `${rmApi.getBaseApiUrl()}/gameserver/timing`, method: 'get'});
		return response.data?.data ?? null;
	} catch {
		return null;
	}
};

// The smallest time of a column, so the quickest car in each can be marked
const fastest = (cars: Array<ITimingCar>, key: 's1' | 's2' | 's3' | 'bestLap') => {
	const times = cars.map(c => c[key]).filter((t): t is number => typeof t === 'number');
	return times.length > 0 ? Math.min(...times) : undefined;
};

const STATE_LABELS: Record<string, string> = {Finished: 'Finished', DNF: 'DNF', Retired: 'DNF', Disqualified: 'DSQ', InGarage: 'Garage', EnteringPits: 'Pits', InPits: 'Pits', ExitingPits: 'Pits'};

// Live timing of the running session, for everyone: positions, laps, sector times and best laps
const LiveTiming = () => {
	const [board, setBoard] = useState<ITimingBoard | null>(null);
	const [loaded, setLoaded] = useState(false);
	const [tracks, setTracks] = useState<Array<ITrackData>>([]);
	const [lapHistory, setLapHistory] = useState(EMPTY_LIVE_LAP_HISTORY);

	useEffect(() => {
		fetchTrackData().then(t => setTracks(Array.isArray(t) ? t : []));
	}, []);

	usePolling(() => {
		fetchTiming().then(b => {
			setBoard(b);
			setLapHistory(prev => updateLiveLapHistory(prev, b));
			setLoaded(true);
		});
	}, 3000);

	const {series, min, max} = useMemo(() => lapSeries(toLapDrivers(lapHistory)), [lapHistory]);

	if (loaded && !board) {
		return <Alert severity="info" sx={{textAlign: 'left'}}>The game server isn&apos;t answering, so there is no live timing right now.</Alert>;
	}

	if (!board) {
		return <Typography>Loading...</Typography>;
	}

	const trackName = tracks.find(t => String(t.id) === board.trackId)?.name.replaceAll('_', ' ');
	const leaderLap = board.cars.find(c => c.position === 1)?.lap;
	const best = {s1: fastest(board.cars, 's1'), s2: fastest(board.cars, 's2'), s3: fastest(board.cars, 's3'), bestLap: fastest(board.cars, 'bestLap')};
	const quickest = (value: number | undefined, key: keyof typeof best) => value !== undefined && value === best[key];

	return (
		<Paper sx={{p: 2, textAlign: 'left'}}>
			<Typography variant="h5" component="h2">Live timing</Typography>
			<Typography variant="body2" color="text.secondary" sx={{mb: 2}} aria-live="polite">
				{[board.stage ? prettyStage(board.stage) : null, trackName, leaderLap && board.raceLength ? `Lap ${leaderLap} of ${board.raceLength}` : null].filter(Boolean).join(' · ')}
			</Typography>

			{board.cars.length === 0
				? <Typography variant="body2" color="text.secondary">Nobody is on track right now.</Typography>
				: <TableContainer>
					<Table size="small" aria-label="Live timing">
						<TableHead>
							<TableRow>
								<TableCell>Pos</TableCell>
								<TableCell>Driver</TableCell>
								<TableCell align="right">Lap</TableCell>
								<TableCell align="right">S1</TableCell>
								<TableCell align="right">S2</TableCell>
								<TableCell align="right">S3</TableCell>
								<TableCell align="right">Last lap</TableCell>
								<TableCell align="right">Best lap</TableCell>
								<TableCell />
							</TableRow>
						</TableHead>
						<TableBody>
							{board.cars.map(c => (
								<TableRow key={c.name}>
									<TableCell><PositionBadge position={c.position} /></TableCell>
									<TableCell component="th" scope="row" sx={{whiteSpace: 'nowrap'}}>{c.name}{!c.player && <Chip size="small" label="AI" variant="outlined" sx={{ml: 1}} />}</TableCell>
									<TableCell align="right">{c.lap ?? ''}</TableCell>
									{(['s1', 's2', 's3'] as const).map((key, i) => (
										<TableCell key={key} align="right"
											sx={{fontWeight: quickest(c[key], key) || c.sector === i + 1 ? 'bold' : undefined, color: quickest(c[key], key) ? TIMING_PURPLE : undefined,
												fontStyle: c.sector === i + 1 ? 'italic' : undefined}}>
											{formatSector(c[key] ?? null)}
										</TableCell>
									))}
									<TableCell align="right">{formatLap(c.lastLap ?? null)}</TableCell>
									<TableCell align="right" sx={{fontWeight: quickest(c.bestLap, 'bestLap') ? 'bold' : undefined, color: quickest(c.bestLap, 'bestLap') ? TIMING_PURPLE : undefined}}>
										{formatLap(c.bestLap ?? null)}
									</TableCell>
									<TableCell>{c.state && c.state !== 'Racing' ? STATE_LABELS[c.state] ?? c.state : ''}</TableCell>
								</TableRow>
							))}
						</TableBody>
					</Table>
				</TableContainer>
			}
			<Typography variant="caption" color="text.secondary" sx={{display: 'block', mt: 1}}>
				Purple is the quickest in the session. The sector a car is in now is in italics and still counting.
			</Typography>
			{series.length > 0 && (
				<Box sx={{mt: 3}}>
					<LapChart series={series} min={min} max={max} subtitle="Player lap evolution (click a name to hide or show it)" />
				</Box>
			)}
		</Paper>
	);
};

export default LiveTiming;
