import React from 'react';

import Box from '@mui/material/Box';

// Gold, silver and bronze for the podium; a plain tag for everyone else
const PODIUM: Record<number, {bg: string, fg: string}> = {
	1: {bg: '#f5c518', fg: '#141414'},
	2: {bg: '#c9ced6', fg: '#141414'},
	3: {bg: '#cd7f32', fg: '#141414'},
};

const PositionBadge = ({position}: {position?: number | null}) => {
	if (!position) {
		return null;
	}

	const podium = PODIUM[position];
	return (
		<Box component="span" aria-label={`Position ${position}`} sx={{
			display: 'inline-flex', alignItems: 'center', justifyContent: 'center', minWidth: 26, height: 22, px: 0.5,
			borderRadius: '3px', fontWeight: 700, fontSize: '0.8rem', fontVariantNumeric: 'tabular-nums',
			bgcolor: podium ? podium.bg : 'action.selected', color: podium ? podium.fg : 'text.primary',
		}}>
			{position}
		</Box>
	);
};

export default PositionBadge;
