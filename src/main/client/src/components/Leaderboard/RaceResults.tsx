import React, {useState, useEffect} from 'react';
import _ from 'lodash';

import {IRace} from './interfaces';
import {ITrackData, IVehicleData} from '../../interfaces/asm2Interfaces';

import {convertEpochDate, convertEpochTime} from '../../utils/epochUtils';
import {convertEpochTimeToRaceResult} from '../../utils/dateTimeUtils';

import {fetchTrackData, fetchVehicleData} from './utilities';
import {SortHeadCell, useSort} from './sorting';
import {roundsOf} from './RoundPoints';
import CopyLinkButton from './CopyLinkButton';
import {atLeast} from '../../utils/roles';
import {useRole} from '../Live/useRole';
import RaceMgrApi from '../../api/RaceMgrApi';

import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Collapse from '@mui/material/Collapse';
import IconButton from '@mui/material/IconButton';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import Paper from '@mui/material/Paper';

import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown';
import KeyboardArrowUpIcon from '@mui/icons-material/KeyboardArrowUp';

interface IProps {
    filteredRaces: Array<IRace>;
    onChanged?: () => void;
}

type SortKey = 'trackName' | 'startDate' | 'gridSize' | 'laps' | 'aiStrength' | 'polePosition' | 'fastestLap' | 'winner';

interface IColumn {
	label: string,
	key?: SortKey,
	text?: boolean,
	align?: 'left' | 'right',
}

const columns: Array<IColumn> = [
	{label: 'Rnd'},
	{label: 'Track', key: 'trackName', text: true},
	{label: 'Start Date', key: 'startDate'},
	{label: 'Start Time'},
	{label: 'End Time'},
	{label: 'Grid Size', key: 'gridSize', align: 'right'},
	{label: 'Laps', key: 'laps', align: 'right'},
	{label: 'AI Strength', key: 'aiStrength', align: 'right'},
	{label: 'Pole Position', key: 'polePosition', text: true, align: 'right'},
	{label: 'Fastest Lap', key: 'fastestLap', text: true, align: 'right'},
	{label: 'Winner', key: 'winner', text: true, align: 'right'},
];

const isHuman = (driverName:string) => driverName.slice(-4) !== '(AI)';

function Row(props: { row: IRace, round: number, trackData: Array<ITrackData>, vehicleData: Array<IVehicleData>, isAdmin: boolean, onChanged?: () => void }) {
	const {row} = props;
	const [open, setOpen] = React.useState(false);
	const [busy, setBusy] = React.useState(false);
	const {isAdmin} = props;

	const setVoided = async (voided: boolean) => {
		if (!row.raceId) {
			return;
		}

		setBusy(true);
		try {
			const rmApi = new RaceMgrApi();
			await rmApi.makeApiCall({url: `${rmApi.getBaseAdminApiUrl()}/race/events/${row.raceId}/void`, method: 'post', data: {voided}});
			props.onChanged?.();
		} finally {
			setBusy(false);
		}
	};

	return (
		<React.Fragment>
			<TableRow sx={{'& > *': {borderBottom: 'unset'}, ...(row.voided && {opacity: 0.55})}}>
				<TableCell>
					<IconButton
						aria-label="expand row"
						size="small"
						onClick={() => setOpen(!open)}
					>
						{open ? <KeyboardArrowUpIcon /> : <KeyboardArrowDownIcon />}
					</IconButton>
				</TableCell>
				<TableCell component="th" scope="row">
					{row.voided ? <Chip size="small" label="Void" /> : props.round}
					{row.raceId && <CopyLinkButton raceId={row.raceId} />}
				</TableCell>
				<TableCell component="th" scope="row">
					{ props.trackData?.filter(td => td.id === row.trackName)[0]?.name.replaceAll('_', ' ') }
				</TableCell>
				<TableCell component="th" scope="row">
					{convertEpochDate(row.startDate)}
				</TableCell>
				<TableCell component="th" scope="row">
					{convertEpochTime(row.startDate)}
				</TableCell>
				<TableCell component="th" scope="row">
					{convertEpochTime(row.endDate)}
				</TableCell>
				<TableCell align="right">{row.gridSize}</TableCell>
				<TableCell align="right">{row.laps}</TableCell>
				<TableCell align="right">{row.aiStrength}</TableCell>
				<TableCell align="right"sx={{
					fontWeight: '',
					...(isHuman(row.polePosition) && {
						fontWeight: 'bold',
					}),
				}}>{row.polePosition}</TableCell>
				<TableCell align="right"sx={{
					fontWeight: '',
					...(isHuman(row.fastestLap) && {
						fontWeight: 'bold',
					}),
				}}>{row.fastestLap}</TableCell>
				<TableCell align="right"sx={{
					fontWeight: '',
					...(isHuman(row.winner) && {
						fontWeight: 'bold',
					}),
				}}>{row.winner}</TableCell>
			</TableRow>
			<TableRow>
				<TableCell style={{paddingBottom: 0, paddingTop: 0}} colSpan={13}>
					<Collapse in={open} timeout="auto" unmountOnExit>
						<Box sx={{margin: 1}}>
							<Typography variant="h6" gutterBottom component="div">
								Race Results
								{row.voided && <Chip size="small" label="Voided: not counted" sx={{ml: 1}} />}
								{isAdmin && <Button size="small" sx={{ml: 2}} disabled={busy} onClick={() => setVoided(!row.voided)}>
									{row.voided ? 'Restore race' : 'Void race'}
								</Button>}
							</Typography>
							<Table size="small" aria-label="purchases">
								<TableHead>
									<TableRow>
										<TableCell align="left">Finish</TableCell>
										<TableCell>Driver</TableCell>
										<TableCell>Vehicle</TableCell>
										<TableCell align="right">Best Qualifying Time</TableCell>
										<TableCell align="right">Best Lap Time</TableCell>
										<TableCell align="right">Total Time</TableCell>
										<TableCell align="right">Points</TableCell>

									</TableRow>
								</TableHead>
								<TableBody>
									{
										row.raceResults?.map(raceResult => (<TableRow key={raceResult.driverName}>
											<TableCell align="left">{raceResult.finishPosition}
											</TableCell>
											<TableCell component="th" scope="row"
												sx={{
													fontWeight: '',
													...(raceResult.isPlayer && {
														fontWeight: 'bold',
													}),
												}}>
												{raceResult.driverName.substring(0, 30)}
											</TableCell>
											<TableCell>
												{ props.vehicleData?.filter(vd => vd.id === raceResult.vehicleId)[0]?.name}
											</TableCell>
											<TableCell align="right" sx={{
												fontWeight: '',
												...(row.qualifyingResults?.filter(q => q.driverName === raceResult.driverName)[0].tookPole === true && {
													fontWeight: 'bold',
												}),
											}}>
												{row.qualifyingResults?.filter(q => q.driverName === raceResult.driverName)[0].tookPole ? '*' : ''}
												{convertEpochTimeToRaceResult(row.qualifyingResults?.filter(q => q.driverName === raceResult.driverName)[0].bestQualifyingTime)}
											</TableCell>
											<TableCell align="right" sx={{
												fontWeight: '',
												...(row.raceResults?.filter(q => q.driverName === raceResult.driverName)[0].hadFastestLap === true && {
													fontWeight: 'bold',
												}),
											}}>
												{row.raceResults?.filter(q => q.driverName === raceResult.driverName)[0].hadFastestLap ? '*' : ''}
												{convertEpochTimeToRaceResult(raceResult.fastestLapTime)}
											</TableCell>
											<TableCell align="right">{convertEpochTimeToRaceResult(raceResult.totalTime) }</TableCell>
											<TableCell align="right">{raceResult.totalPoints}</TableCell>
										</TableRow>))
									}
								</TableBody>
							</Table>
						</Box>
					</Collapse>
				</TableCell>
			</TableRow>
		</React.Fragment>
	);
}

const RaceResults = (props:IProps) => {
	// Voiding a race changes the standings: admins only (the server checks too). Asked once here, not per row.
	const isAdmin = atLeast(useRole().role, 'ADMIN');
	const [trackData, setTrackData] = React.useState<Array<ITrackData>>([]);
	const [vehicleData, setVehicleData] = React.useState<Array<IVehicleData>>([]);

	const {orderBy, order, requestSort} = useSort<SortKey>('startDate', 'desc');

	useEffect(() => {
		fetchTrackData().then(td => setTrackData(td));
		fetchVehicleData().then(vd => setVehicleData(vd));
	}, []);

	// Round numbers follow the order the races were run, whichever way the table is sorted
	const roundNumbers = React.useMemo(() => new Map<IRace, number>(roundsOf(props.filteredRaces.filter(r => !r.voided)).map((race, i) => [race, i + 1])), [props.filteredRaces]);

	const sortedRaces = React.useMemo(() => {
		const trackName = (r: IRace) => (trackData.find(td => td.id === r.trackName)?.name ?? r.trackName ?? '').toLowerCase();
		const iteratee = orderBy === 'trackName' ? trackName : orderBy;
		return _.orderBy(props.filteredRaces, [iteratee, 'startDate'], [order, 'desc']);
	}, [props.filteredRaces, trackData, orderBy, order]);

	return (
		<Box sx={{marginTop: '10px'}}>
			<TableContainer component={Paper} sx={{maxHeight: '75vh'}}>
				<Table stickyHeader size="small" aria-label="collapsible table">
					<TableHead>
						<TableRow>
							<TableCell />
							{columns.map(col => (
								col.key
									? <SortHeadCell key={col.key} sortKey={col.key} label={col.label} align={col.align} text={col.text}
										orderBy={orderBy} order={order} onSort={requestSort} />
									: <TableCell key={col.label} align={col.align}>{col.label}</TableCell>
							))}
						</TableRow>
					</TableHead>
					<TableBody>
						{sortedRaces.map((row, idx) => (
							<Row key={idx} row={row} isAdmin={isAdmin} onChanged={props.onChanged} round={roundNumbers.get(row) ?? 0} trackData={trackData} vehicleData={vehicleData} />
						))}
					</TableBody>
				</Table>
			</TableContainer>
		</Box>
	);
};

export default RaceResults;
