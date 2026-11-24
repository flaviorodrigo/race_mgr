import React, {useState, useEffect} from 'react';

import Typography from '@mui/material/Typography';
import Paper from '@mui/material/Paper';
import {Link as RouterLink} from 'react-router-dom';

const ServerConfigError = () => (
	<Paper sx={{
		p: 2,
		pb: 5,
		display: 'flex',
		flexDirection: 'column',
	}}>
		<Typography variant="h5" align="left" gutterBottom={true}>
				Not set up yet
		</Typography>

		<Typography variant="subtitle1" align="left" gutterBottom={true} sx={{maxWidth: 500}}>
				The leaderboard shows the active championship, and none is chosen yet. An admin signs in, opens <RouterLink to="/manageserver">Manage Server</RouterLink>, creates a session (the championship) and a rotation, selects both and presses <em>Set active</em>. Races finished from then on appear here.
		</Typography>

	</Paper>
);

export default ServerConfigError;
