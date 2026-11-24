import React, {useEffect, useState} from 'react';
import {useSelector} from 'react-redux';

import {fetchRestartStatus, restartGameServer} from '../Live/adminApi';
import {fetchLiveSession} from '../Live/liveApi';

import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogContentText from '@mui/material/DialogContentText';
import DialogTitle from '@mui/material/DialogTitle';
import FormControlLabel from '@mui/material/FormControlLabel';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';

const BACK_TIMEOUT_MS = 3 * 60 * 1000;
// The game server's entrypoint checks for the request every 2 seconds
const PICKUP_TIMEOUT_MS = 30 * 1000;

// Restart for a game server that runs in its own container: asks for it, then waits until the server answers again
const GameServerRestart = () => {
	const {serverStatus} = useSelector((state: any) => state);
	const [open, setOpen] = useState(false);
	const [resetRotation, setResetRotation] = useState(false);
	const [phase, setPhase] = useState<'idle' | 'restarting' | 'back' | 'slow' | 'not-picked-up' | 'error'>('idle');
	const [message, setMessage] = useState('');

	// While restarting: the game server's entrypoint takes the request (the signal file goes away), then the server
	// answers again. A restart is quick, so its going away isn't always seen: the picked-up request is what counts.
	useEffect(() => {
		if (phase !== 'restarting') {
			return undefined;
		}

		const started = Date.now();
		let pickedUp = false;
		const timer = setInterval(async () => {
			if (!pickedUp) {
				const status = await fetchRestartStatus();
				pickedUp = status.ok && status.data?.pending === false;
				if (!pickedUp && Date.now() - started > PICKUP_TIMEOUT_MS) {
					setPhase('not-picked-up');
				}

				return;
			}

			const live = await fetchLiveSession(true);
			if (live) {
				setPhase('back');
			} else if (Date.now() - started > BACK_TIMEOUT_MS) {
				setPhase('slow');
			}
		}, 3000);
		return () => clearInterval(timer);
	}, [phase]);

	if (serverStatus.managed !== false || serverStatus.restartable === false) {
		return null;
	}

	const restart = async () => {
		setOpen(false);
		const result = await restartGameServer(resetRotation);
		setMessage(result.message);
		setPhase(result.ok ? 'restarting' : 'error');
	};

	return (
		<Stack spacing={1} sx={{textAlign: 'left', mt: 2}}>
			<Stack direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{alignItems: {sm: 'center'}}}>
				<Button variant="outlined" color="warning" disabled={phase === 'restarting'} onClick={() => setOpen(true)}>Restart game server</Button>
				<Typography variant="body2" color="text.secondary">
					Applies a new rotation or server settings. Everyone connected is dropped.
				</Typography>
			</Stack>
			{phase === 'restarting' && <Alert severity="info">{message} Waiting for it to answer again...</Alert>}
			{phase === 'back' && <Alert severity="success" onClose={() => setPhase('idle')}>The game server is back.</Alert>}
			{phase === 'slow' && <Alert severity="warning" onClose={() => setPhase('idle')}>
				The game server hasn&apos;t answered for three minutes. Check its container on the NAS (docker compose logs).
			</Alert>}
			{phase === 'not-picked-up' && <Alert severity="warning" onClose={() => setPhase('idle')}>
				The game server hasn&apos;t picked up the restart request. Is its container using Race Manager&apos;s entrypoint
				(docker/game-server/entrypoint.sh)? The request is the file .racemanager-restart in the server folder.
			</Alert>}
			{phase === 'error' && <Alert severity="error" onClose={() => setPhase('idle')}>{message}</Alert>}

			<Dialog open={open} onClose={() => setOpen(false)}>
				<DialogTitle>Restart the game server?</DialogTitle>
				<DialogContent>
					<DialogContentText sx={{mb: 1}}>
						It stops and starts again, reading its files anew (rotation, server.cfg, ban list...). Anyone connected is dropped, and a
						race in progress is lost.
					</DialogContentText>
					<FormControlLabel control={<Checkbox checked={resetRotation} onChange={e => setResetRotation(e.target.checked)} />}
						label="Start the rotation again from its first round" />
				</DialogContent>
				<DialogActions>
					<Button onClick={() => setOpen(false)}>Cancel</Button>
					<Button color="warning" onClick={restart}>Restart</Button>
				</DialogActions>
			</Dialog>
		</Stack>
	);
};

export default GameServerRestart;
