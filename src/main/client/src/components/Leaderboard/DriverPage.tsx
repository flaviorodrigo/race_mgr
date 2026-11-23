import React, {useEffect, useMemo, useState} from 'react';
import {Link as RouterLink, useParams, useSearchParams} from 'react-router-dom';

import RaceMgrApi from '../../api/RaceMgrApi';
import {IRace} from './interfaces';
import {ITrackData} from '../../interfaces/asm2Interfaces';
import {fetchRaceData, fetchTrackData} from './utilities';
import {IScoring} from '../../utils/pointsCalculator';
import {computeStandings} from '../../utils/standings';
import {convertEpochDate} from '../../utils/epochUtils';
import {convertEpochTimeToRaceResult} from '../../utils/dateTimeUtils';

import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Link from '@mui/material/Link';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';

const Stat = ({label, value}: {label: string, value: string | number}) => (
	<Paper variant="outlined" sx={{p: 1.5, minWidth: 110, textAlign: 'center'}}>
		<Typography variant="h5" component="div">{value}</Typography>
		<Typography variant="caption" color="text.secondary">{label}</Typography>
	</Paper>
);

// One driver's season in a session: results round by round, and their best lap at each track
const DriverPage = () => {
	const {name = ''} = useParams();
	const [search] = useSearchParams();
	const sessionId = search.get('session') ?? '';

	const [races, setRaces] = useState<Array<IRace> | null>(null);
	const [scoring, setScoring] = useState<IScoring | null>(null);
	const [tracks, setTracks] = useState<Array<ITrackData>>([]);

	useEffect(() => {
		const load = async () => {
			if (!sessionId) {
				setRaces([]);
				return;
			}

			let sessionScoring: IScoring | null = null;
			try {
				const rmApi = new RaceMgrApi();
				const session = await rmApi.makeApiCall({url: `${rmApi.getBaseApiUrl()}/race/session/${sessionId}`, method: 'get'});
				sessionScoring = session.data?.data?.scoring ?? null;
			} catch {
				// Built-in formula
			}

			setScoring(sessionScoring);
			const all = await fetchRaceData(sessionId, 'All', sessionScoring);
			setRaces((all as Array<IRace> | undefined)?.filter(r => r.finished && !r.voided) ?? []);
		};

		load();
		fetchTrackData().then(t => setTracks(Array.isArray(t) ? t : []));
	}, [sessionId]);

	const summary = useMemo(() => {
		if (!races) {
			return null;
		}

		const standings = computeStandings(races, scoring);
		const row = standings.rows.find(r => r.name === name);
		if (!row) {
			return {row: null, rounds: [], stats: null, rank: 0};
		}

		const rounds = standings.rounds as Array<IRace>;
		const entries = rounds.map((race, index) => {
			const result = race.raceResults?.find(r => r.driverName === name);
			const qualiIndex = race.qualifyingResults?.findIndex(q => q.driverName === name) ?? -1;
			return {
				race, index, result,
				qualifying: qualiIndex >= 0 ? qualiIndex + 1 : null,
				track: tracks.find(t => t.id === race.trackName)?.name.replaceAll('_', ' ') ?? String(race.trackName ?? ''),
			};
		}).filter(e => e.result);

		const positions = entries.map(e => e.result!.finishPosition);
		const stats = {
			starts: entries.length,
			wins: positions.filter(p => p === 1).length,
			podiums: positions.filter(p => p <= 3).length,
			poles: entries.filter(e => e.qualifying === 1).length,
			fastestLaps: entries.filter(e => e.result!.hadFastestLap).length,
			best: positions.length ? Math.min(...positions) : 0,
			average: positions.length ? (positions.reduce((a, b) => a + b, 0) / positions.length).toFixed(1) : '-',
		};
		return {row, rounds: entries, stats, rank: standings.rows.indexOf(row) + 1};
	}, [races, scoring, name, tracks]);

	if (summary === null) {
		return <Typography>Loading...</Typography>;
	}

	if (!summary.row || !summary.stats) {
		return (
			<Alert severity="info" sx={{textAlign: 'left'}}>
				No results for {name} in this championship.{' '}
				<Link component={RouterLink} to="/">Back to the leaderboard</Link>
			</Alert>
		);
	}

	const {row, rounds, stats, rank} = summary;

	return (
		<Paper sx={{p: 2, textAlign: 'left'}}>
			<Typography variant="h5" component="h2">{name}</Typography>
			<Typography variant="body2" color="text.secondary" sx={{mb: 2}}>Championship position {rank} · {row.total} points</Typography>

			<Stack direction="row" useFlexGap sx={{flexWrap: 'wrap', gap: 1.5, mb: 3}}>
				<Stat label="Races" value={stats.starts} />
				<Stat label="Wins" value={stats.wins} />
				<Stat label="Podiums" value={stats.podiums} />
				<Stat label="Poles" value={stats.poles} />
				<Stat label="Fastest laps" value={stats.fastestLaps} />
				<Stat label="Best finish" value={stats.best} />
				<Stat label="Average finish" value={stats.average} />
			</Stack>

			<Typography variant="h6">Results</Typography>
			<TableContainer component={Paper} variant="outlined" sx={{mb: 2}}>
				<Table size="small" aria-label="Driver results">
					<TableHead>
						<TableRow>
							<TableCell>Round</TableCell>
							<TableCell>Date</TableCell>
							<TableCell>Track</TableCell>
							<TableCell align="right">Qualified</TableCell>
							<TableCell align="right">Finished</TableCell>
							<TableCell align="right">Best lap</TableCell>
							<TableCell align="right">Points</TableCell>
						</TableRow>
					</TableHead>
					<TableBody>
						{rounds.map(e => (
							<TableRow key={e.index} hover>
								<TableCell>
									{e.race.raceId
										? <Link component={RouterLink} to={`/race/${e.race.raceId}`}>R{e.index + 1}</Link>
										: `R${e.index + 1}`}
								</TableCell>
								<TableCell>{convertEpochDate(e.race.startDate)?.toString()}</TableCell>
								<TableCell>{e.track}</TableCell>
								<TableCell align="right">{e.qualifying ?? ''}</TableCell>
								<TableCell align="right">{e.result?.finishPosition}</TableCell>
								<TableCell align="right" sx={{fontWeight: e.result?.hadFastestLap ? 'bold' : undefined}}>
									{e.result && e.result.fastestLapTime > 0 ? convertEpochTimeToRaceResult(e.result.fastestLapTime) : ''}
								</TableCell>
								<TableCell align="right" sx={{
									textDecoration: row.dropped[e.index] ? 'line-through' : undefined,
									color: row.dropped[e.index] ? 'text.disabled' : undefined,
								}}>
									{e.result?.totalPoints}
								</TableCell>
							</TableRow>
						))}
					</TableBody>
				</Table>
			</TableContainer>
			{row.adjustment !== 0 && <Box sx={{mb: 2}}><Typography variant="body2">Adjustment: {row.adjustment > 0 ? '+' : ''}{row.adjustment} points</Typography></Box>}

			<Link component={RouterLink} to="/">Back to the leaderboard</Link>
		</Paper>
	);
};

export default DriverPage;
