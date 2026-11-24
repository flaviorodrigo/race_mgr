import React from 'react';

import {useSkin} from '../../skin/skin';

import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import SportsScoreIcon from '@mui/icons-material/SportsScore';
import TableRowsIcon from '@mui/icons-material/TableRows';

// Switches between the racing look and the classic one
const SkinToggle = () => {
	const {racing, setRacing} = useSkin();
	const label = racing ? 'Switch to the classic look' : 'Switch to the racing look';
	return (
		<Tooltip title={label}>
			<IconButton color="inherit" aria-label={label} onClick={() => setRacing(!racing)}>
				{racing ? <TableRowsIcon /> : <SportsScoreIcon />}
			</IconButton>
		</Tooltip>
	);
};

export default SkinToggle;
