import React, {useState} from 'react';

import {usePolling} from '../../utils/usePolling';
import {fetchLiveSession, prettyStage, ILiveSession} from './liveApi';

import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';

// "Who is on the server right now", for everyone. Shows nothing when the game server isn't reachable.
const LiveStrip = () => {
	const [live, setLive] = useState<ILiveSession | null>(null);

	usePolling(() => {
		fetchLiveSession(false).then(setLive);
	}, 10000);

	if (!live) {
		return null;
	}

	return (
		<Box aria-live="polite" sx={{mb: 2}}>
			<Stack direction="row" spacing={1} sx={{alignItems: 'center', flexWrap: 'wrap', rowGap: 1}}>
				<Box component="span" aria-hidden sx={{width: 10, height: 10, borderRadius: '50%', bgcolor: 'success.main', flexShrink: 0}} />
				<Typography variant="subtitle2" component="span">
					{live.players}{live.maxPlayers ? ` / ${live.maxPlayers}` : ''} online
					{live.stage ? ` · ${prettyStage(live.stage)}` : ''}
				</Typography>
				{live.members.map(m => (
					<Chip key={m.name} size="small" variant="outlined"
						label={m.ping !== undefined && m.ping !== null ? `${m.name} · ${m.ping} ms` : m.name} />
				))}
			</Stack>
		</Box>
	);
};

export default LiveStrip;
