import React, {useEffect, useMemo, useState} from 'react';
import {Link as RouterLink} from 'react-router-dom';

import {fetchRaceData, fetchTrackData, fetchVehicleData} from './utilities';
import {IRace} from './interfaces';
import {ITrackData, IVehicleData} from '../../interfaces/asm2Interfaces';
import {careerStats, trackRecords} from '../../utils/careerStats';
import {convertEpochDate} from '../../utils/epochUtils';
import {convertEpochTimeToRaceResult} from '../../utils/dateTimeUtils';

import Link from '@mui/material/Link';
import Paper from '@mui/material/Paper';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';

// Every race the server has seen, across all championships: career totals and the lap record at each track
const Records = () => {
	const [races, setRaces] = useState<Array<IRace> | null>(null);
	const [tracks, setTracks] = useState<Array<ITrackData>>([]);
	const [vehicles, setVehicles] = useState<Array<IVehicleData>>([]);

	useEffect(() => {
		fetchRaceData('All', 'All', null).then(r => setRaces((r as Array<IRace> | undefined) ?? []));
		fetchTrackData().then(t => setTracks(Array.isArray(t) ? t : []));
		fetchVehicleData().then(v => setVehicles(Array.isArray(v) ? v : []));
	}, []);

	const drivers = useMemo(() => careerStats(races ?? []), [races]);
	const records = useMemo(() => trackRecords(races ?? []), [races]);

	if (!races) {
		return <Typography>Loading...</Typography>;
	}

	const trackName = (id: string) => tracks.find(t => String(t.id) === id)?.name.replaceAll('_', ' ') ?? id;
	const vehicleName = (id?: string) => (id ? vehicles.find(v => String(v.id) === String(id))?.name ?? '' : '');

	return (
		<Paper sx={{p: 2, textAlign: 'left'}}>
			<Typography variant="h5" component="h2">Records</Typography>
			<Typography variant="body2" color="text.secondary" sx={{mb: 2}}>
				Every finished race on this server, in all championships. Voided races and AI drivers are left out.
			</Typography>

			{drivers.length === 0
				? <Typography variant="body2" color="text.secondary">No races yet.</Typography>
				: <>
					<Typography variant="h6">Drivers</Typography>
					<TableContainer component={Paper} variant="outlined" sx={{mb: 3}}>
						<Table size="small" aria-label="Career statistics">
							<TableHead>
								<TableRow>
									<TableCell>Driver</TableCell>
									<TableCell align="right">Races</TableCell>
									<TableCell align="right">Wins</TableCell>
									<TableCell align="right">Podiums</TableCell>
									<TableCell align="right">Poles</TableCell>
									<TableCell align="right">Fastest laps</TableCell>
									<TableCell align="right">Best finish</TableCell>
									<TableCell align="right">Avg finish</TableCell>
									<TableCell align="right">DNF</TableCell>
									<TableCell align="right">DSQ</TableCell>
								</TableRow>
							</TableHead>
							<TableBody>
								{drivers.map(d => (
									<TableRow key={d.name}>
										<TableCell component="th" scope="row">{d.name}</TableCell>
										<TableCell align="right">{d.races}</TableCell>
										<TableCell align="right">{d.wins}</TableCell>
										<TableCell align="right">{d.podiums}</TableCell>
										<TableCell align="right">{d.poles}</TableCell>
										<TableCell align="right">{d.fastestLaps}</TableCell>
										<TableCell align="right">{d.bestFinish || ''}</TableCell>
										<TableCell align="right">{d.averageFinish.toFixed(1)}</TableCell>
										<TableCell align="right">{d.dnfs}</TableCell>
										<TableCell align="right">{d.disqualifications}</TableCell>
									</TableRow>
								))}
							</TableBody>
						</Table>
					</TableContainer>

					<Typography variant="h6">Track records</Typography>
					<TableContainer component={Paper} variant="outlined" sx={{mb: 2}}>
						<Table size="small" aria-label="Track records">
							<TableHead>
								<TableRow>
									<TableCell>Track</TableCell>
									<TableCell>Driver</TableCell>
									<TableCell>Car</TableCell>
									<TableCell align="right">Lap</TableCell>
									<TableCell align="right">Set</TableCell>
								</TableRow>
							</TableHead>
							<TableBody>
								{records.map(r => ({...r, track: trackName(r.trackId)})).sort((a, b) => a.track.localeCompare(b.track)).map(r => (
									<TableRow key={r.trackId}>
										<TableCell component="th" scope="row">{r.track}</TableCell>
										<TableCell>{r.driver}</TableCell>
										<TableCell>{vehicleName(r.vehicleId)}</TableCell>
										<TableCell align="right">{convertEpochTimeToRaceResult(r.time)}{r.qualifying ? ' (qualifying)' : ''}</TableCell>
										<TableCell align="right">{convertEpochDate(r.date)?.toString()}</TableCell>
									</TableRow>
								))}
							</TableBody>
						</Table>
					</TableContainer>
				</>
			}
			<Link component={RouterLink} to="/">Back to the leaderboard</Link>
		</Paper>
	);
};

export default Records;
