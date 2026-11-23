import React, {useMemo} from 'react';
import _ from 'lodash';

import {IRace} from './interfaces';
import {convertEpochDate} from '../../utils/epochUtils';

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
}

// Rounds are the session's races in the order they were run
export const roundsOf = (races: Array<IRace>) => _.orderBy(races, ['startDate'], ['asc']);

// The classic championship grid: one row per driver, one column per round, and the total.
const RoundPoints = (props: IProps) => {
	const rounds = useMemo(() => roundsOf(props.filteredRaces), [props.filteredRaces]);

	const rows = useMemo(() => {
		const byDriver = new Map<string, Array<number | null>>();
		rounds.forEach((race, roundIdx) => {
			race.raceResults?.forEach(result => {
				if (!byDriver.has(result.driverName)) {
					byDriver.set(result.driverName, rounds.map(() => null));
				}

				(byDriver.get(result.driverName) as Array<number | null>)[roundIdx] = result.totalPoints ?? 0;
			});
		});

		const drivers = Array.from(byDriver, ([name, points]) => ({name, points, total: _.sum(points.map(p => p ?? 0))}));
		return _.orderBy(drivers, ['total', d => d.name.toLowerCase()], ['desc', 'asc']);
	}, [rounds]);

	const stickyCell = {position: 'sticky', left: 0, bgcolor: 'background.paper', zIndex: 1} as const;

	return (
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
						<TableCell align="right" sx={{fontWeight: 'bold'}}>Total</TableCell>
					</TableRow>
				</TableHead>
				<TableBody>
					{rows.map(row => (
						<TableRow key={row.name} hover>
							<TableCell component="th" scope="row" sx={stickyCell}>{row.name.substring(0, 30)}</TableCell>
							{row.points.map((p, idx) => (
								<TableCell key={idx} align="right" sx={{color: p === null ? 'text.disabled' : undefined}}>
									{p === null ? '–' : p}
								</TableCell>
							))}
							<TableCell align="right" sx={{fontWeight: 'bold'}}>{row.total}</TableCell>
						</TableRow>
					))}
				</TableBody>
			</Table>
		</TableContainer>
	);
};

export default RoundPoints;
