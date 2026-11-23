import React, {useMemo} from 'react';

import {IRace} from './interfaces';
import {IScoring} from '../../utils/pointsCalculator';
import {computeStandings} from '../../utils/standings';

import Paper from '@mui/material/Paper';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';

interface IProps {
	filteredRaces: Array<IRace>;
	scoring?: IScoring | null;
}

// Team championship: a team's points are its drivers' totals added up (after dropped rounds and adjustments)
const TeamStandings = (props: IProps) => {
	const teams = useMemo(() => computeStandings(props.filteredRaces, props.scoring).teams, [props.filteredRaces, props.scoring]);

	if (teams.length === 0) {
		return null;
	}

	return (
		<TableContainer component={Paper} sx={{marginTop: '10px'}}>
			<Table size="small" aria-label="Team standings">
				<TableHead>
					<TableRow>
						<TableCell>#</TableCell>
						<TableCell>Team</TableCell>
						<TableCell>Drivers</TableCell>
						<TableCell align="right">Points</TableCell>
					</TableRow>
				</TableHead>
				<TableBody>
					{teams.map((team, idx) => (
						<TableRow key={team.name} hover>
							<TableCell>{idx + 1}</TableCell>
							<TableCell component="th" scope="row" sx={{fontWeight: 'bold'}}>{team.name}</TableCell>
							<TableCell>{team.drivers.join(', ')}</TableCell>
							<TableCell align="right" sx={{fontWeight: 'bold'}}>{team.total}</TableCell>
						</TableRow>
					))}
				</TableBody>
			</Table>
		</TableContainer>
	);
};

export default TeamStandings;
