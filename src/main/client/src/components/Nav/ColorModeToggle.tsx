import React from 'react';
import {useColorScheme} from '@mui/material/styles';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import DarkModeIcon from '@mui/icons-material/DarkMode';
import LightModeIcon from '@mui/icons-material/LightMode';

// Switches between light and dark. Until the user picks one, the system setting applies.
const ColorModeToggle = () => {
	const {mode, systemMode, setMode} = useColorScheme();
	if (!mode) {
		return null;
	}

	const isDark = (mode === 'system' ? systemMode : mode) === 'dark';
	const label = isDark ? 'Switch to light mode' : 'Switch to dark mode';

	return (
		<Tooltip title={label}>
			<IconButton color="inherit" aria-label={label} onClick={() => setMode(isDark ? 'light' : 'dark')}>
				{isDark ? <LightModeIcon /> : <DarkModeIcon />}
			</IconButton>
		</Tooltip>
	);
};

export default ColorModeToggle;
