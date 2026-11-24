import React from 'react';

import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';

export interface IHighlight {
	label: string,
	value: string,
	detail?: string,
	accent: string,
}

// A row of headline facts (winner, pole, fastest lap...), like the captions on a race broadcast
const RaceHighlights = ({items, label}: {items: Array<IHighlight>, label: string}) => (
	<Box aria-label={label} sx={{display: 'grid', gap: 1.5, gridTemplateColumns: {xs: '1fr', sm: `repeat(${Math.min(items.length, 3)}, 1fr)`}, mb: 2}}>
		{items.filter(i => i.value).map(i => (
			<Paper key={i.label} variant="outlined" sx={{p: 1.5, borderLeft: 4, borderLeftColor: i.accent}}>
				<Typography variant="caption" color="text.secondary" sx={{textTransform: 'uppercase', letterSpacing: '0.08em'}}>{i.label}</Typography>
				<Typography variant="h6" component="div" sx={{lineHeight: 1.2, overflowWrap: 'anywhere'}}>{i.value}</Typography>
				{i.detail && <Typography variant="body2" color="text.secondary">{i.detail}</Typography>}
			</Paper>
		))}
	</Box>
);

export default RaceHighlights;
