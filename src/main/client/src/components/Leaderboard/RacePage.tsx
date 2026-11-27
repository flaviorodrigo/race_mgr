import React, {useEffect, useState} from 'react';
import {useParams, Link as RouterLink} from 'react-router-dom';

import RaceMgrApi from '../../api/RaceMgrApi';
import {IRace} from './interfaces';
import {ITrackData, IVehicleData} from '../../interfaces/asm2Interfaces';
import {fetchTrackData, fetchVehicleData, mapRaceDataToRaceResultsObject} from './utilities';
import {convertEpochDate, convertEpochTime} from '../../utils/epochUtils';
import {convertEpochTimeToRaceResult} from '../../utils/dateTimeUtils';
import CopyLinkButton from './CopyLinkButton';
import LapAnalysis from './LapAnalysis';
import PositionBadge from '../common/PositionBadge';
import RaceHighlights from '../common/RaceHighlights';
import {TIMING_PURPLE} from '../../skin/colors';
import RaceNotesDialog from './RaceNotesDialog';

import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Link from '@mui/material/Link';
import Paper from '@mui/material/Paper';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';

type State = {status: 'loading'} | {status: 'missing'} | {status: 'unfinished'} | {status: 'ready', race: IRace};

const loadRace = async (raceId: string): Promise<State> => {
	const rmApi = new RaceMgrApi();
	try {
		const response = await rmApi.makeApiCall({url: `${rmApi.getBaseApiUrl()}/race/events/${raceId}`, method: 'get'});
		const raw = response.data?.data;
		if (!raw) {
			return {status: 'missing'};
		}

		// Points follow the scoring of the session the race belongs to
		let scoring = null;
		if (raw.race_session_id) {
			try {
				const session = await rmApi.makeApiCall({url: `${rmApi.getBaseApiUrl()}/race/session/${raw.race_session_id}`, method: 'get'});
				scoring = session.data?.data?.scoring ?? null;
			} catch {
				// Fall back to the built-in formula
			}
		}

		const [race] = mapRaceDataToRaceResultsObject([raw], scoring);
		return race ? {status: 'ready', race} : {status: 'unfinished'};
	} catch {
		return {status: 'missing'};
	}
};

const time = (ms?: number) => (ms && ms > 0 ? convertEpochTimeToRaceResult(ms) : undefined);

// Winner, pole and fastest lap, with their times
const highlightsOf = (race: IRace, results: IRace['raceResults']) => [
	{label: 'Winner', value: race.winner ?? '', detail: time(results[0]?.totalTime), accent: '#f5c518'},
	{label: 'Pole position', value: race.polePosition ?? '', detail: time(race.qualifyingResults?.[0]?.bestQualifyingTime), accent: '#e10600'},
	{label: 'Fastest lap', value: race.fastestLap ?? '', detail: time(results.find(r => r.hadFastestLap)?.fastestLapTime), accent: TIMING_PURPLE},
];

// A race on its own page, for sharing a link to one result
const RacePage = () => {
	const {raceId = ''} = useParams();
	const [state, setState] = useState<State>({status: 'loading'});
	const [tracks, setTracks] = useState<Array<ITrackData>>([]);
	const [vehicles, setVehicles] = useState<Array<IVehicleData>>([]);
	const [editing, setEditing] = useState(false);
	const isAdmin = new RaceMgrApi().hasSession();

	useEffect(() => {
		loadRace(raceId).then(setState);
		fetchTrackData().then(t => setTracks(Array.isArray(t) ? t : []));
		fetchVehicleData().then(v => setVehicles(Array.isArray(v) ? v : []));
	}, [raceId]);

	if (state.status === 'loading') {
		return <Typography>Loading...</Typography>;
	}

	if (state.status !== 'ready') {
		return (
			<Alert severity="info" sx={{textAlign: 'left'}}>
				{state.status === 'missing' ? 'This race could not be found.' : 'This race has no result yet.'}{' '}
				<Box sx={{display: 'flex', gap: 2}}>
					<Link component={RouterLink} to="/races">Back to races</Link>
					<Link component={RouterLink} to="/">Back to the leaderboard</Link>
				</Box>
			</Alert>
		);
	}

	const {race} = state;
	const trackName = tracks.find(t => String(t.id) === String(race.trackName))?.name?.replaceAll('_', ' ');
	const vehicleName = (id: string) => vehicles.find(v => String(v.id) === String(id))?.name ?? '';
	const results = [...(race.raceResults ?? [])].sort((a, b) => a.finishPosition - b.finishPosition);

	return (
		<Paper sx={{p: 2, textAlign: 'left'}}>
			<Box sx={{display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1}}>
				<Typography variant="h5" component="h2">{trackName ?? 'Race'}</Typography>
				<Box sx={{display: 'flex', gap: 1, flexWrap: 'wrap'}}>
					{race.videoUrl && <Button component="a" href={race.videoUrl} target="_blank" rel="noopener noreferrer">Watch the race</Button>}
					{isAdmin && <Button onClick={() => setEditing(true)}>Penalties and video</Button>}
					<CopyLinkButton raceId={raceId} />
				</Box>
			</Box>
			<Typography variant="body2" color="text.secondary" sx={{mb: 2}}>
				{convertEpochDate(race.startDate)?.toString()} · {convertEpochTime(race.startDate)} to {convertEpochTime(race.endDate)}
				{' · '}{race.gridSize} drivers · {race.laps} laps
			</Typography>

			<RaceHighlights label="Race highlights" items={highlightsOf(race, results)} />

			<Typography variant="h6">Result</Typography>
			<TableContainer component={Paper} variant="outlined" sx={{mb: 3}}>
				<Table size="small" aria-label="Race result">
					<TableHead>
						<TableRow>
							<TableCell>Pos</TableCell>
							<TableCell>Driver</TableCell>
							<TableCell>Vehicle</TableCell>
							<TableCell align="right">Best lap</TableCell>
							<TableCell align="right">Total time</TableCell>
							<TableCell align="right">Points</TableCell>
						</TableRow>
					</TableHead>
					<TableBody>
						{results.map(r => (
							<TableRow key={r.driverName}>
								<TableCell><PositionBadge position={r.finishPosition} /></TableCell>
								<TableCell component="th" scope="row" sx={{fontWeight: r.hadFastestLap ? 'bold' : undefined}}>
									{r.driverName}
									{r.state === 'Disqualified' && <Chip size="small" label="DSQ" color="error" sx={{ml: 1}} />}
									{r.state === 'Retired' && <Chip size="small" label="DNF" sx={{ml: 1}} />}
									{r.penaltySeconds !== undefined && <Chip size="small" color="warning" label={`+${r.penaltySeconds} s`} title={r.penaltyNote} sx={{ml: 1}} />}
								</TableCell>
								<TableCell>{vehicleName(r.vehicleId)}</TableCell>
								<TableCell align="right" sx={{fontWeight: r.hadFastestLap ? 'bold' : undefined}}>
									{r.hadFastestLap ? '* ' : ''}{r.fastestLapTime > 0 ? convertEpochTimeToRaceResult(r.fastestLapTime) : ''}
								</TableCell>
								<TableCell align="right">{r.totalTime > 0 ? convertEpochTimeToRaceResult(r.totalTime) : ''}</TableCell>
								<TableCell align="right">{r.totalPoints}</TableCell>
							</TableRow>
						))}
					</TableBody>
				</Table>
			</TableContainer>

			{race.qualifyingResults?.length > 0 && <>
				<Typography variant="h6">Qualifying</Typography>
				<TableContainer component={Paper} variant="outlined" sx={{mb: 2}}>
					<Table size="small" aria-label="Qualifying result">
						<TableHead>
							<TableRow>
								<TableCell>Pos</TableCell>
								<TableCell>Driver</TableCell>
								<TableCell align="right">Best lap</TableCell>
							</TableRow>
						</TableHead>
						<TableBody>
							{/* The game server lists qualifying results fastest first */}
							{race.qualifyingResults.map((r, idx) => (
								<TableRow key={r.driverName}>
									<TableCell>{idx + 1}</TableCell>
									<TableCell component="th" scope="row">{r.driverName}</TableCell>
									<TableCell align="right">{r.bestQualifyingTime > 0 ? convertEpochTimeToRaceResult(r.bestQualifyingTime) : ''}</TableCell>
								</TableRow>
							))}
						</TableBody>
					</Table>
				</TableContainer>
			</>}

			{race.drivers && race.drivers.length > 0 && <LapAnalysis drivers={race.drivers} title="Race pace" />}
			{race.qualifyingDrivers && race.qualifyingDrivers.length > 0 && <LapAnalysis drivers={race.qualifyingDrivers} title="Qualifying pace" />}

			<Box sx={{display: 'flex', gap: 2}}>
				<Link component={RouterLink} to="/races">Back to races</Link>
				<Link component={RouterLink} to="/">Back to the leaderboard</Link>
			</Box>

			{editing && <RaceNotesDialog raceId={raceId} drivers={results.map(r => r.driverName)} penalties={race.penalties ?? []} videoUrl={race.videoUrl ?? ''}
				onClose={() => setEditing(false)}
				onSaved={() => {
					setEditing(false);
					loadRace(raceId).then(setState);
				}} />}
		</Paper>
	);
};

export default RacePage;
