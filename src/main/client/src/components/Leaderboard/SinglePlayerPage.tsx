import React, {useEffect, useMemo, useRef, useState} from 'react';
import {Link as RouterLink} from 'react-router-dom';

import RaceMgrApi from '../../api/RaceMgrApi';
import {fetchTrackData, mapRaceDataToRaceResultsObject} from './utilities';
import {availableTracks, filterAndSortRaces, trackLabel} from './racesFilter';
import {IRace} from './interfaces';
import {ITrackData} from '../../interfaces/asm2Interfaces';
import {convertEpochDate, convertEpochTime} from '../../utils/epochUtils';
import DateFilter from './DateFilter';

import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import FormControl from '@mui/material/FormControl';
import InputLabel from '@mui/material/InputLabel';
import Link from '@mui/material/Link';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Select from '@mui/material/Select';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';

interface ISpSession {
	id: string;
	name: string;
}

// User-owned single-player races (kept separate from multiplayer Leader Board & Races)
const SinglePlayerPage: React.FC = () => {
	const isAuthenticated = new RaceMgrApi().hasSession();
	const [sessions, setSessions] = useState<Array<ISpSession>>([]);
	const [selectedSessionId, setSelectedSessionId] = useState('All');
	const [startDate, setStartDate] = useState(0);
	const [endDate, setEndDate] = useState(0);
	const [trackId, setTrackId] = useState('All');
	const [races, setRaces] = useState<Array<IRace>>([]);
	const [tracks, setTracks] = useState<Array<ITrackData>>([]);
	const [loading, setLoading] = useState(false);
	const [importMessage, setImportMessage] = useState<string | null>(null);
	const [reloadTick, setReloadTick] = useState(0);
	const fileInputRef = useRef<HTMLInputElement | null>(null);

	useEffect(() => {
		fetchTrackData().then(t => setTracks(Array.isArray(t) ? t : []));
	}, []);

	useEffect(() => {
		if (!isAuthenticated) {
			return;
		}

		const rmApi = new RaceMgrApi();
		rmApi.makeApiCall({url: `${rmApi.getBaseAdminApiUrl()}/single-player/sessions?limit=100`, method: 'get'})
			.then(resp => setSessions(resp.data?.data?.content ?? []))
			.catch(() => setSessions([]));
	}, [isAuthenticated, reloadTick]);

	useEffect(() => {
		if (!isAuthenticated) {
			return;
		}

		setLoading(true);
		const rmApi = new RaceMgrApi();
		const q = selectedSessionId && selectedSessionId !== 'All' ? `?raceSessionId=${encodeURIComponent(selectedSessionId)}&limit=1000` : '?limit=1000';
		rmApi.makeApiCall({url: `${rmApi.getBaseAdminApiUrl()}/single-player/races${q}`, method: 'get'})
			.then(resp => {
				const raw = resp.data?.data?.content ?? [];
				const mapped = mapRaceDataToRaceResultsObject(raw, null).filter(x => x.finished === true);
				setRaces(mapped);
				setTrackId('All');
				if (mapped.length > 0) {
					const ordered = [...mapped].sort((a, b) => a.startDate - b.startDate);
					setStartDate(ordered[0].startDate);
					setEndDate(ordered[ordered.length - 1].endDate);
				} else {
					setStartDate(0);
					setEndDate(0);
				}

				setLoading(false);
			})
			.catch(() => {
				setRaces([]);
				setLoading(false);
			});
	}, [isAuthenticated, selectedSessionId, reloadTick]);

	const handleImportFile = async (event: React.ChangeEvent<HTMLInputElement>) => {
		const file = event.target.files?.[0];
		event.target.value = '';
		if (!file) {
			return;
		}

		try {
			const text = await file.text();
			const rmApi = new RaceMgrApi();
			const resp = await rmApi.makeApiCall({
				url: `${rmApi.getBaseApiUrl()}/race/ingest`,
				method: 'post',
				data: text,
				headers: {'Content-Type': 'application/json'},
			});
			const info = resp.data?.data;
			setImportMessage(`Imported ${info?.imported ?? 0} race(s) into "${info?.sessionName ?? 'Single Player'}" (${info?.skipped ?? 0} already present).`);
			setReloadTick(t => t + 1);
		} catch {
			setImportMessage('Could not import race JSON. Check that the file is valid ams2_stats JSON.');
		}
	};

	const trackOptions = useMemo(() => availableTracks(races, tracks), [races, tracks]);
	const visibleRaces = useMemo(
		() => filterAndSortRaces(races, {startDate, endDate, trackId}),
		[races, startDate, endDate, trackId],
	);

	if (!isAuthenticated) {
		return (
			<Alert severity="info" sx={{textAlign: 'left'}}>
				Sign in to view your single-player races. <Link component={RouterLink} to="/login">Sign in</Link>
			</Alert>
		);
	}

	return (
		<Paper sx={{p: 2, pb: 4, textAlign: 'left'}}>
			<Box sx={{display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1}}>
				<Box>
					<Typography variant="overline" color="text.secondary" sx={{lineHeight: 1.5}}>Single Player</Typography>
					<Typography variant="h5" component="h2" gutterBottom>My single-player races</Typography>
				</Box>
				<>
					<input ref={fileInputRef} type="file" accept=".json,application/json" hidden onChange={handleImportFile} />
					<Button variant="outlined" size="small" onClick={() => fileInputRef.current?.click()}>
						Import Race JSON
					</Button>
				</>
			</Box>
			{importMessage && <Alert severity="info" sx={{mb: 2}} onClose={() => setImportMessage(null)}>{importMessage}</Alert>}

			<Box sx={{display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 2, mt: 1, mb: 2}}>
				<FormControl variant="standard" sx={{minWidth: 200}} size="small">
					<InputLabel id="sp-session-select">Session</InputLabel>
					<Select
						labelId="sp-session-select"
						value={selectedSessionId}
						label="Session"
						onChange={e => setSelectedSessionId(e.target.value)}
					>
						<MenuItem value="All">All my sessions</MenuItem>
						{sessions.map(s => (
							<MenuItem key={s.id} value={s.id}>{s.name}</MenuItem>
						))}
					</Select>
				</FormControl>

				<DateFilter
					currentSessionId={selectedSessionId}
					startDate={startDate}
					setStartDate={setStartDate}
					endDate={endDate}
					setEndDate={setEndDate}
				/>

				<FormControl variant="standard" sx={{minWidth: 200}} size="small">
					<InputLabel id="sp-track-select">Track</InputLabel>
					<Select
						labelId="sp-track-select"
						value={trackId}
						label="Track"
						onChange={e => setTrackId(e.target.value)}
					>
						<MenuItem value="All">All tracks</MenuItem>
						{trackOptions.map(t => (
							<MenuItem key={t.id} value={t.id}>{t.name}</MenuItem>
						))}
					</Select>
				</FormControl>
			</Box>

			{loading ? (
				<Box sx={{display: 'flex', justifyContent: 'center', py: 4}}>
					<CircularProgress />
				</Box>
			) : visibleRaces.length === 0 ? (
				<Typography variant="body2" color="text.secondary">
					No single-player races yet. Record races with SimHub or use Import Race JSON.
				</Typography>
			) : (
				<TableContainer component={Paper} variant="outlined">
					<Table size="small" aria-label="Single-player races">
						<TableHead>
							<TableRow>
								<TableCell>Date</TableCell>
								<TableCell>Track</TableCell>
								<TableCell>Winner</TableCell>
								<TableCell>Pole</TableCell>
								<TableCell>Fastest lap</TableCell>
								<TableCell align="right">Laps</TableCell>
								<TableCell align="right">Grid</TableCell>
								<TableCell />
							</TableRow>
						</TableHead>
						<TableBody>
							{visibleRaces.map(r => (
								<TableRow key={r.raceId ?? r.startDate} hover>
									<TableCell sx={{whiteSpace: 'nowrap'}}>
										{convertEpochDate(r.startDate)?.toString()} {convertEpochTime(r.startDate)}
									</TableCell>
									<TableCell>
										{r.raceId ? (
											<Link component={RouterLink} to={`/race/${r.raceId}`} underline="hover" sx={{fontWeight: 'bold'}}>
												{trackLabel(r.trackName, tracks)}
											</Link>
										) : trackLabel(r.trackName, tracks)}
									</TableCell>
									<TableCell>{r.winner ?? ''}</TableCell>
									<TableCell>{r.polePosition ?? ''}</TableCell>
									<TableCell>{r.fastestLap ?? ''}</TableCell>
									<TableCell align="right">{r.laps}</TableCell>
									<TableCell align="right">{r.gridSize}</TableCell>
									<TableCell align="right">
										{r.raceId && (
											<Button size="small" component={RouterLink} to={`/race/${r.raceId}`}>
												Details &amp; charts
											</Button>
										)}
									</TableCell>
								</TableRow>
							))}
						</TableBody>
					</Table>
				</TableContainer>
			)}
		</Paper>
	);
};

export default SinglePlayerPage;
