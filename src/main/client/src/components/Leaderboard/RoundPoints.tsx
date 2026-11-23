import React, {useMemo} from 'react';
import _ from 'lodash';

import {IRace} from './interfaces';
import {IScoring} from '../../utils/pointsCalculator';
import {computeStandings} from '../../utils/standings';
import {convertEpochDate} from '../../utils/epochUtils';
import {downloadCsv} from '../../utils/csv';

import Button from '@mui/material/Button';
import Paper from '@mui/material/Paper';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Tooltip from '@mui/material/Tooltip';

interface IProps {
	filteredRaces: Array<IRace>;
	scoring?: IScoring | null;
}

// Rounds are the session's races in the order they were run
export const roundsOf = (races: Array<IRace>) => _.orderBy(races, ['startDate'], ['asc']);

// The classic championship grid: one row per driver, one column per round, dropped rounds struck through,
// manual adjustments, and the total.
const RoundPoints = (props: IProps) => {
	const {rounds, rows} = useMemo(() => computeStandings(props.filteredRaces, props.scoring), [props.filteredRaces, props.scoring]);
	const hasAdjustments = rows.some(r => r.adjustment !== 0);
	const stickyCell = {position: 'sticky', left: 0, bgcolor: 'background.paper', zIndex: 1} as const;

	const exportCsv = () => downloadCsv('points-by-round.csv', [
		['Driver', ...rounds.map((_r, i) => `R${i + 1}`), ...(hasAdjustments ? ['Adjustment'] : []), 'Total'],
		...rows.map(r => [
			r.name,
			...r.perRound.map((p, i) => (p === null ? '' : (r.dropped[i] ? `(${p})` : p))),
			...(hasAdjustments ? [r.adjustment] : []),
			r.total,
		]),
	]);

	return (
		<>
			<TableContainer component={Paper} sx={{maxHeight: '70vh', marginTop: '10px'}}>
				<Table stickyHeader size="small" aria-label="Points by round">
					<TableHead>
						<TableRow>
							<TableCell sx={{...stickyCell, zIndex: 3}}>Driver</TableCell>
							{rounds.map((race, idx) => (
								<TableCell key={idx} align="right">
									<Tooltip title={convertEpochDate(race.startDate)?.toString() ?? ''}>
										<span>R{idx + 1}</span>
									</Tooltip>
								</TableCell>
							))}
							{hasAdjustments && <TableCell align="right">Adj.</TableCell>}
							<TableCell align="right" sx={{fontWeight: 'bold'}}>Total</TableCell>
						</TableRow>
					</TableHead>
					<TableBody>
						{rows.map(row => (
							<TableRow key={row.name} hover>
								<TableCell component="th" scope="row" sx={stickyCell}>{row.name.substring(0, 30)}</TableCell>
								{row.perRound.map((p, idx) => (
									<TableCell key={idx} align="right" sx={{
										color: p === null || row.dropped[idx] ? 'text.disabled' : undefined,
										textDecoration: row.dropped[idx] ? 'line-through' : undefined,
									}}>
										{p === null ? '–' : p}
									</TableCell>
								))}
								{hasAdjustments && <TableCell align="right">{row.adjustment === 0 ? '' : (row.adjustment > 0 ? '+' : '') + row.adjustment}</TableCell>}
								<TableCell align="right" sx={{fontWeight: 'bold'}}>{row.total}</TableCell>
							</TableRow>
						))}
					</TableBody>
				</Table>
			</TableContainer>
			{rows.length > 0 && <Button size="small" onClick={exportCsv} sx={{mt: 1, float: 'left'}}>Export CSV</Button>}
		</>
	);
};

export default RoundPoints;
