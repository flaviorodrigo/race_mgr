import React, {useEffect, useState} from 'react';
import {useSelector} from 'react-redux';

import {fetchRestartStatus, fetchRotationAddon, restartGameServer, startRotationOver} from '../Live/adminApi';
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
	// With the racemgr_rotate addon in use, the rotation starts over without a restart
	const [addonReady, setAddonReady] = useState(false);
	const [confirmStartOver, setConfirmStartOver] = useState(false);
	const [startOver, setStartOver] = useState<{ok: boolean, text: string} | null>(null);

	useEffect(() => {
		fetchRotationAddon().then(r => setAddonReady(Boolean(r.ok && r.data?.ready && r.data.reported)));
	}, []);

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

	const doStartOver = async () => {
		setConfirmStartOver(false);
		const result = await startRotationOver();
		setStartOver({ok: result.ok, text: result.message});
	};

	const restart = async () => {
		setOpen(false);
		// The new addon keeps its own position: ask it to start over too, so the restart begins at round 1
		if (resetRotation && addonReady) {
			await startRotationOver();
		}

		const result = await restartGameServer(resetRotation);
		setMessage(result.message);
		setPhase(result.ok ? 'restarting' : 'error');
	};

	return (
		<Stack spacing={1} sx={{textAlign: 'left', mt: 2}}>
			<Stack direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{alignItems: {sm: 'center'}}}>
				<Button variant="outlined" color="warning" disabled={phase === 'restarting'} onClick={() => setOpen(true)}>Restart game server</Button>
				<Typography variant="body2" color="text.secondary">
					{addonReady
						? 'Applies server settings (server.cfg, ban list). Everyone connected is dropped. Rotation changes don\'t need it.'
						: 'Applies a new rotation or server settings. Everyone connected is dropped.'}
				</Typography>
			</Stack>
			{addonReady && <Stack direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{alignItems: {sm: 'center'}}}>
				<Button variant="outlined" onClick={() => setConfirmStartOver(true)}>Start the rotation from round 1</Button>
				<Typography variant="body2" color="text.secondary">
					No restart: the next session is round 1. Nobody is dropped, and an open session keeps its settings.
				</Typography>
			</Stack>}
			{startOver && <Alert severity={startOver.ok ? 'success' : 'error'} onClose={() => setStartOver(null)}>{startOver.text}</Alert>}
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

			<Dialog open={confirmStartOver} onClose={() => setConfirmStartOver(false)}>
				<DialogTitle>Start the rotation from round 1?</DialogTitle>
				<DialogContent>
					<DialogContentText>
						The next session the game server opens is round 1 of the active rotation. The game server keeps running: nobody is
						disconnected, and a lobby or race that is open now carries on with its settings.
					</DialogContentText>
				</DialogContent>
				<DialogActions>
					<Button onClick={() => setConfirmStartOver(false)}>Cancel</Button>
					<Button onClick={doStartOver}>Start from round 1</Button>
				</DialogActions>
			</Dialog>

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
