import React, {useState, useEffect, useMemo} from 'react';
import _ from 'lodash';

import {IRace} from './interfaces';
import {SortHeadCell, useSort} from './sorting';
import {IScoring} from '../../utils/pointsCalculator';
import {computeStandings} from '../../utils/standings';
import {downloadCsv} from '../../utils/csv';

import {styled} from '@mui/material/styles';

import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell, {tableCellClasses} from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Button from '@mui/material/Button';
import Link from '@mui/material/Link';
import {Link as RouterLink} from 'react-router-dom';
import Typography from '@mui/material/Typography';

interface IProps {
    filteredRaces: Array<IRace>;
    scoring?: IScoring | null;
    sessionId?: string;
}

interface IPlayerSummary {
    rank?: number,
    totalRaces: number,
    name:string,
	isPlayer: boolean,
    firstPlaceFinishes: number,
    secondPlaceFinishes:number,
    thirdPlaceFinishes:number,
    numberOfPodiums: number,
    numberOfPolePositions: number,
    numberOfFastestLaps: number,
    totalPoints:number
}

const DEFAULT_SUMMARY_COUNT = 5;

type SortKey = 'totalRaces' | 'name' | 'firstPlaceFinishes' | 'secondPlaceFinishes' | 'thirdPlaceFinishes'
	| 'numberOfPodiums' | 'numberOfPolePositions' | 'numberOfFastestLaps' | 'totalPoints';

const columns: Array<{key: SortKey, label: string, text?: boolean, left?: boolean}> = [
	{key: 'totalRaces', label: 'Races', left: true},
	{key: 'name', label: 'Driver', text: true},
	{key: 'firstPlaceFinishes', label: 'Wins'},
	{key: 'secondPlaceFinishes', label: 'Seconds'},
	{key: 'thirdPlaceFinishes', label: 'Thirds'},
	{key: 'numberOfPodiums', label: 'Podiums'},
	{key: 'numberOfPolePositions', label: 'Poles'},
	{key: 'numberOfFastestLaps', label: 'Fastest Laps'},
	{key: 'totalPoints', label: 'Total Points'},
];

const StyledTableCell = styled(TableCell)(({theme}) => ({
	[`&.${tableCellClasses.head}`]: {
		backgroundColor: theme.palette.primary.dark,
		color: theme.palette.common.white,
	},
	[`&.${tableCellClasses.body}`]: {
		fontSize: 14,
	},
}));

const StyledTableRow = styled(TableRow)(({theme}) => ({
	'&:nth-of-type(odd)': {
		backgroundColor: theme.palette.action.hover,
	},
	// Hide last border
	'&:last-child td, &:last-child th': {
		border: 0,
	},
}));

const getTotalPointsForDriver = (driverName:string, races: Array<IRace>) => {
	const points:Array<number> = [];
	races.forEach(race => {
		points.push(race.raceResults.filter(rr => rr.driverName === driverName)[0]?.totalPoints);
	});

	return _.sum(points);
};

const getParticipantList = (filteredRaces: Array<IRace>):Array<string> => {
	const results:Array<string> = [];

	filteredRaces.forEach(race => {
		race.participants.forEach(part => {
			if (!results.includes(part)) {
				results.push(part);
			}
		});
	});

	return results;
};

const isPlayer = (driverName:string):boolean => {
	if (driverName.slice(-4) !== '(AI)') {
		return true;
	}

	return false;
};

const generateRaceSummary = (filteredRaces: Array<IRace>):Array<IPlayerSummary> => {
	const playerSummary:Array<IPlayerSummary> = [];

	getParticipantList(filteredRaces).forEach(p => {
		const participantSummary: IPlayerSummary = {
			isPlayer: isPlayer(p),
			totalRaces: filteredRaces.filter(r =>
				p === r.raceResults.filter(rr => rr.driverName === p)[0]?.driverName,
			).length,
			name: p,
			firstPlaceFinishes: filteredRaces.filter(r =>
				p === r.raceResults.filter(rr => rr.driverName === p && rr.finishPosition === 1)[0]?.driverName,
			).length,
			secondPlaceFinishes: filteredRaces.filter(r =>
				p === r.raceResults.filter(rr => rr.driverName === p && rr.finishPosition === 2)[0]?.driverName,
			).length,
			thirdPlaceFinishes: filteredRaces.filter(r =>
				p === r.raceResults.filter(rr => rr.driverName === p && rr.finishPosition === 3)[0]?.driverName,
			).length,
			numberOfPodiums: filteredRaces.filter(r =>
				p === r.raceResults.filter(rr =>
					rr.driverName === p
                    && (rr.finishPosition === 1 || rr.finishPosition === 2 || rr.finishPosition === 3),
				)[0]?.driverName,
			).length,
			numberOfPolePositions: filteredRaces.filter(fr => fr.polePosition === p).length,
			numberOfFastestLaps: filteredRaces.filter(fr => fr.fastestLap === p).length,
			totalPoints: getTotalPointsForDriver(p, filteredRaces),
		};
		playerSummary.push(participantSummary);
	});
	return playerSummary;
};

const RaceSummary = (props:IProps) => {
	const [maxSummary, setMaxSummary] = useState(DEFAULT_SUMMARY_COUNT);
	const {orderBy, order, requestSort} = useSort<SortKey>('totalPoints', 'desc');

	// Rank always means "position by points", whatever column the table is sorted by
	const rows = useMemo(() => {
		// Totals follow the championship rules (dropped rounds, adjustments)
		const totals = new Map(computeStandings(props.filteredRaces, props.scoring).rows.map(r => [r.name, r.total]));
		const ranked = _.orderBy(generateRaceSummary(props.filteredRaces).map(r => ({...r, totalPoints: totals.get(r.name) ?? r.totalPoints})), ['totalPoints'], ['desc'])
			.map((r, i) => ({...r, rank: i + 1}));
		const key = orderBy === 'name' ? (r: IPlayerSummary) => r.name.toLowerCase() : orderBy;
		return _.orderBy(ranked, [key, 'totalPoints', (r: IPlayerSummary) => r.name.toLowerCase()], [order, 'desc', 'asc']);
	}, [props.filteredRaces, props.scoring, orderBy, order]);

	useEffect(() => {
		setMaxSummary(DEFAULT_SUMMARY_COUNT);
	}, [props.filteredRaces]);

	const exportCsv = () => downloadCsv('standings.csv', [
		['Rank', 'Driver', 'Races', 'Wins', 'Seconds', 'Thirds', 'Podiums', 'Poles', 'Fastest laps', 'Points'],
		...rows.map(r => [r.rank,
			r.name,
			r.totalRaces,
			r.firstPlaceFinishes,
			r.secondPlaceFinishes,
			r.thirdPlaceFinishes,
			r.numberOfPodiums,
			r.numberOfPolePositions,
			r.numberOfFastestLaps,
			r.totalPoints]),
	]);

	return (<Box sx={{marginTop: '10px'}}>
		<TableContainer component={Paper} sx={{maxHeight: '70vh'}}>
			<Table stickyHeader sx={{minWidth: 650}} aria-label="Race Summary" size="small">
				<TableHead>
					<StyledTableRow>
						<StyledTableCell>#</StyledTableCell>
						{columns.map(col => (
							<SortHeadCell key={col.key} cell={StyledTableCell} sortKey={col.key} label={col.label}
								align={col.text || col.left ? 'left' : 'right'} text={col.text}
								orderBy={orderBy} order={order} onSort={requestSort} />
						))}
					</StyledTableRow>
				</TableHead>
				<TableBody>
					{_.take(rows, maxSummary).map((row:IPlayerSummary) => (
						<StyledTableRow
							key={row.name}
							sx={{'&:last-child td, &:last-child th': {border: 0}}}
						>
							<StyledTableCell component="th" scope="row">
								{row.rank}
							</StyledTableCell>
							<StyledTableCell component="th" scope="row">
								{row.totalRaces}
							</StyledTableCell>
							<StyledTableCell component="th" scope="row" sx={{
								fontWeight: '',
								...(row.isPlayer && {
									fontWeight: 'bold',
								}),
							}}>
								{props.sessionId && !row.name.endsWith('(AI)')
									? <Link component={RouterLink} to={`/driver/${encodeURIComponent(row.name)}?session=${props.sessionId}`} underline="hover" color="inherit">{row.name.substring(0, 30)}</Link>
									: row.name.substring(0, 30)}
							</StyledTableCell>
							<StyledTableCell align="right">{row.firstPlaceFinishes}</StyledTableCell>
							<StyledTableCell align="right">{row.secondPlaceFinishes}</StyledTableCell>
							<StyledTableCell align="right">{row.thirdPlaceFinishes}</StyledTableCell>
							<StyledTableCell align="right">{row.numberOfPodiums}</StyledTableCell>

							<StyledTableCell align="right">{row.numberOfPolePositions}</StyledTableCell>
							<StyledTableCell align="right">{row.numberOfFastestLaps}</StyledTableCell>
							<StyledTableCell align="right">{row.totalPoints}</StyledTableCell>
						</StyledTableRow>
					))}
				</TableBody>
			</Table>
		</TableContainer>
		<Typography align="left">
			<Button size="small" onClick={exportCsv} sx={{mr: 2}}>Export CSV</Button>
			<Link
				component="button"
				variant="body2"
				underline="none"
				onClick={() => {
					setMaxSummary(maxSummary === DEFAULT_SUMMARY_COUNT ? 10000 : DEFAULT_SUMMARY_COUNT);
				}}
			>
				{maxSummary === DEFAULT_SUMMARY_COUNT ? 'Show More' : 'Show Less'}
			</Link>
		</Typography>
	</Box>);
};

export default RaceSummary;
