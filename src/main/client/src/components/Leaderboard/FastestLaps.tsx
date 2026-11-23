import React, {useEffect, useMemo, useState} from 'react';
import _ from 'lodash';

import {IRace} from './interfaces';
import {ITrackData} from '../../interfaces/asm2Interfaces';
import {fetchTrackData} from './utilities';
import {convertEpochDate} from '../../utils/epochUtils';
import {convertEpochTimeToRaceResult} from '../../utils/dateTimeUtils';
import {SortHeadCell, useSort} from './sorting';

import Paper from '@mui/material/Paper';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';

interface IProps {
	filteredRaces: Array<IRace>;
}

type SortKey = 'track' | 'time' | 'driver' | 'date';

interface IBestLap {
	track: string,
	time: number,
	driver: string,
	date: number,
}

// The quickest lap anyone set at each track, from qualifying or the race
const FastestLaps = (props: IProps) => {
	const [tracks, setTracks] = useState<Array<ITrackData>>([]);
	const {orderBy, order, requestSort} = useSort<SortKey>('track', 'asc');

	useEffect(() => {
		fetchTrackData().then(t => setTracks(Array.isArray(t) ? t : []));
	}, []);

	const rows = useMemo(() => {
		const best = new Map<string, IBestLap>();
		props.filteredRaces.forEach(race => {
			const track = tracks.find(t => t.id === race.trackName)?.name.replaceAll('_', ' ') ?? String(race.trackName ?? '');
			const laps = [
				...(race.raceResults ?? []).map(r => ({driver: r.driverName, time: r.fastestLapTime})),
				...(race.qualifyingResults ?? []).map(r => ({driver: r.driverName, time: r.bestQualifyingTime})),
			].filter(l => l.time > 0);

			laps.forEach(lap => {
				const current = best.get(track);
				if (!current || lap.time < current.time) {
					best.set(track, {track, time: lap.time, driver: lap.driver, date: race.startDate});
				}
			});
		});

		const key = orderBy === 'track' || orderBy === 'driver' ? (r: IBestLap) => r[orderBy].toLowerCase() : orderBy;
		return _.orderBy(Array.from(best.values()), [key, 'time'], [order, 'asc']);
	}, [props.filteredRaces, tracks, orderBy, order]);

	if (rows.length === 0) {
		return null;
	}

	return (
		<TableContainer component={Paper} sx={{maxHeight: '60vh', marginTop: '10px'}}>
			<Table stickyHeader size="small" aria-label="Fastest laps">
				<TableHead>
					<TableRow>
						<SortHeadCell sortKey="track" label="Track" text orderBy={orderBy} order={order} onSort={requestSort} />
						<SortHeadCell sortKey="time" label="Fastest lap" align="right" text orderBy={orderBy} order={order} onSort={requestSort} />
						<SortHeadCell sortKey="driver" label="Driver" text orderBy={orderBy} order={order} onSort={requestSort} />
						<SortHeadCell sortKey="date" label="Date" align="right" orderBy={orderBy} order={order} onSort={requestSort} />
					</TableRow>
				</TableHead>
				<TableBody>
					{rows.map(r => (
						<TableRow key={r.track} hover>
							<TableCell component="th" scope="row">{r.track}</TableCell>
							<TableCell align="right" sx={{fontWeight: 'bold'}}>{convertEpochTimeToRaceResult(r.time)}</TableCell>
							<TableCell>{r.driver}</TableCell>
							<TableCell align="right">{convertEpochDate(r.date)?.toString()}</TableCell>
						</TableRow>
					))}
				</TableBody>
			</Table>
		</TableContainer>
	);
};

export default FastestLaps;
