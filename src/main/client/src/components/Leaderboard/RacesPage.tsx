import React, {useEffect, useMemo, useRef, useState} from 'react';
import {Link as RouterLink} from 'react-router-dom';
import {useDispatch, useSelector} from 'react-redux';

import RaceMgrApi from '../../api/RaceMgrApi';
import {getServerConfig} from '../../state/serverConfig';
import {getSessions} from '../../state/sessions';
import {getRotations} from '../../state/rotations';
import {fetchRaceData, fetchTrackData} from './utilities';
import {availableTracks, filterAndSortRaces, trackLabel} from './racesFilter';
import {IRace} from './interfaces';
import {ITrackData} from '../../interfaces/asm2Interfaces';
import {convertEpochDate, convertEpochTime} from '../../utils/epochUtils';
import SessionsFilter from './ServerConfig/SessionsFilter';
import DateFilter from './DateFilter';

import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
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

// Browseable list of finished races with session, date-range and track filters; each row opens RacePage (/race/:id)
const RacesPage: React.FC = () => {
	const dispatch = useDispatch();
	const {serverConfig, sessions} = useSelector((state: any) => state);

	const [selectedSessionId, setSelectedSessionId] = useState('');
	const [selectedRotationId, setSelectedRotationId] = useState('');
	const [startDate, setStartDate] = useState(0);
	const [endDate, setEndDate] = useState(0);
	const [trackId, setTrackId] = useState('All');
	const [races, setRaces] = useState<Array<IRace>>([]);
	const [tracks, setTracks] = useState<Array<ITrackData>>([]);
	const [loading, setLoading] = useState(false);
	const [importMessage, setImportMessage] = useState<string | null>(null);
	const [reloadTick, setReloadTick] = useState(0);
	const fileInputRef = useRef<HTMLInputElement | null>(null);
	const canImport = new RaceMgrApi().hasSession();

	useEffect(() => {
		dispatch(getServerConfig());
		dispatch(getSessions());
		dispatch(getRotations());
		fetchTrackData().then(t => setTracks(Array.isArray(t) ? t : []));
	}, []);

	useEffect(() => {
		if (!serverConfig.isLoading) {
			setSelectedSessionId(serverConfig.activeRaceSessionId || 'All');
		}
	}, [serverConfig.isLoading, serverConfig.activeRaceSessionId]);

	useEffect(() => {
		if (!serverConfig.isLoading) {
			setSelectedRotationId(serverConfig.activeRaceRotationId || 'All');
		}
	}, [serverConfig.isLoading, serverConfig.activeRaceRotationId]);

	useEffect(() => {
		if (!selectedSessionId || !selectedRotationId) {
			setRaces([]);
			return;
		}

		setLoading(true);
		const scoring = sessions.sessions?.find((s: any) => s.id === selectedSessionId)?.scoring ?? null;
		fetchRaceData(selectedSessionId, selectedRotationId, scoring).then(r => {
			const finished = ((r as Array<IRace>) ?? []).filter(x => x.finished === true);
			setRaces(finished);
			setTrackId('All');
			if (finished.length > 0) {
				const ordered = [...finished].sort((a, b) => a.startDate - b.startDate);
				setStartDate(ordered[0].startDate);
				setEndDate(ordered[ordered.length - 1].endDate);
			} else {
				setStartDate(0);
				setEndDate(0);
			}

			setLoading(false);
		});
	}, [selectedSessionId, selectedRotationId, sessions.sessions, reloadTick]);

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
			dispatch(getSessions());
			setReloadTick(t => t + 1);
		} catch {
			setImportMessage('Could not import race JSON. Check that the file is valid ams2_stats JSON and you have Steward/Admin rights.');
		}
	};

	const trackOptions = useMemo(() => availableTracks(races, tracks), [races, tracks]);
	const visibleRaces = useMemo(
		() => filterAndSortRaces(races, {startDate, endDate, trackId}),
		[races, startDate, endDate, trackId],
	);

	return (
		<Paper sx={{p: 2, pb: 4, textAlign: 'left'}}>
			<Box sx={{display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1}}>
				<Box>
					<Typography variant="overline" color="text.secondary" sx={{lineHeight: 1.5}}>Races</Typography>
					<Typography variant="h5" component="h2" gutterBottom>Race results &amp; lap charts</Typography>
				</Box>
				{canImport && (
					<>
						<input ref={fileInputRef} type="file" accept=".json,application/json" hidden onChange={handleImportFile} />
						<Button variant="outlined" size="small" onClick={() => fileInputRef.current?.click()}>
							Import Race JSON
						</Button>
					</>
				)}
			</Box>
			{importMessage && <Alert severity="info" sx={{mb: 2}} onClose={() => setImportMessage(null)}>{importMessage}</Alert>}

			<SessionsFilter
				selectedSessionId={selectedSessionId}
				setSelectedSessionId={setSelectedSessionId}
				selectedRotationId={selectedRotationId}
				setSelectedRotationId={setSelectedRotationId}
			/>

			<Box sx={{display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 2, mt: 1, mb: 2}}>
				<DateFilter
					currentSessionId={serverConfig.activeRaceSessionId}
					startDate={startDate}
					setStartDate={setStartDate}
					endDate={endDate}
					setEndDate={setEndDate}
				/>
				<FormControl variant="standard" sx={{minWidth: 220}} size="small">
					<InputLabel id="races-track-select">Track</InputLabel>
					<Select
						labelId="races-track-select"
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

			{serverConfig.isLoading || loading ? (
				<Box sx={{display: 'flex', justifyContent: 'center', py: 4}}>
					<CircularProgress />
				</Box>
			) : visibleRaces.length === 0 ? (
				<Typography variant="body2" color="text.secondary">No races match the selected filters.</Typography>
			) : (
				<TableContainer component={Paper} variant="outlined">
					<Table size="small" aria-label="Races">
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
							{visibleRaces.map(r => {
								const humans = (r.raceResults ?? []).filter(d => d.isPlayer).length;
								return (
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
											{r.voided && <Chip size="small" label="Voided" color="warning" sx={{ml: 1}} />}
										</TableCell>
										<TableCell>{r.winner ?? ''}</TableCell>
										<TableCell>{r.polePosition ?? ''}</TableCell>
										<TableCell>{r.fastestLap ?? ''}</TableCell>
										<TableCell align="right">{r.laps}</TableCell>
										<TableCell align="right">{r.gridSize} ({humans} human)</TableCell>
										<TableCell align="right">
											{r.raceId && (
												<Button size="small" component={RouterLink} to={`/race/${r.raceId}`}>
													Details &amp; charts
												</Button>
											)}
										</TableCell>
									</TableRow>
								);
							})}
						</TableBody>
					</Table>
				</TableContainer>
			)}
		</Paper>
	);
};

export default RacesPage;
