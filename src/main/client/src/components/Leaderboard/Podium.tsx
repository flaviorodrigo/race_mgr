import React from 'react';
import {Link as RouterLink} from 'react-router-dom';

import PositionBadge from '../common/PositionBadge';

import Box from '@mui/material/Box';
import Link from '@mui/material/Link';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';

export interface IPodiumEntry {
	name: string,
	points: number,
	wins: number,
}

// The top three of the championship, champion in the middle on the highest step
const Podium = ({entries, sessionId}: {entries: Array<IPodiumEntry>, sessionId?: string}) => {
	if (entries.length === 0) {
		return null;
	}

	// 2 - 1 - 3 on wide screens; 1, 2, 3 stacked on phones
	const order = entries.length >= 3 ? [1, 0, 2] : entries.map((_e, i) => i);
	const heights = [148, 124, 108];
	return (
		<Box aria-label="Championship podium" sx={{display: 'flex', gap: 1.5, alignItems: 'flex-end', justifyContent: 'center', mb: 2, flexWrap: {xs: 'wrap', sm: 'nowrap'}}}>
			{order.map(i => {
				const entry = entries[i];
				const name = sessionId && !entry.name.endsWith('(AI)')
					? <Link component={RouterLink} to={`/driver/${encodeURIComponent(entry.name)}?session=${sessionId}`} color="inherit" underline="hover">{entry.name}</Link>
					: entry.name;
				return (
					<Paper key={entry.name} variant="outlined" sx={{
						flex: {xs: '1 1 100%', sm: '0 1 240px'}, minHeight: {sm: heights[i]}, p: 1.5, textAlign: 'center',
						display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', order: {xs: i, sm: 'initial'},
						borderTop: 4, borderTopColor: ['#f5c518', '#c9ced6', '#cd7f32'][i],
					}}>
						<Box><PositionBadge position={i + 1} /></Box>
						<Typography variant="h6" component="div" sx={{mt: 0.5, overflowWrap: 'anywhere'}}>{name}</Typography>
						<Typography variant="body2" color="text.secondary">
							{entry.points} pts · {entry.wins} win{entry.wins === 1 ? '' : 's'}
						</Typography>
					</Paper>
				);
			})}
		</Box>
	);
};

export default Podium;
