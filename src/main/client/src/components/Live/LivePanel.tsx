import React, {useState} from 'react';

import {usePolling} from '../../utils/usePolling';
import {fetchLiveSession, kickPlayer, sendChat, prettyStage, ILiveSession, ILiveMember} from './liveApi';

import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogContentText from '@mui/material/DialogContentText';
import DialogTitle from '@mui/material/DialogTitle';
import FormControl from '@mui/material/FormControl';
import InputLabel from '@mui/material/InputLabel';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Select from '@mui/material/Select';
import Snackbar from '@mui/material/Snackbar';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';

const MAX_CHAT = 200;

// The game server bans for a number of seconds; 0 means just remove the player
const BAN_OPTIONS = [
	{label: 'Kick only (they can rejoin)', seconds: 0},
	{label: 'Kick and ban for 10 minutes', seconds: 10 * 60},
	{label: 'Kick and ban for 1 hour', seconds: 60 * 60},
	{label: 'Kick and ban for 1 day', seconds: 24 * 60 * 60},
	{label: 'Kick and ban for 7 days', seconds: 7 * 24 * 60 * 60},
	{label: 'Kick and ban for 30 days', seconds: 30 * 24 * 60 * 60},
];

// Admin view of the running session: who is connected, kick a player, message the lobby.
const LivePanel = () => {
	const [live, setLive] = useState<ILiveSession | null>(null);
	const [loaded, setLoaded] = useState(false);
	const [toKick, setToKick] = useState<ILiveMember | null>(null);
	const [banSeconds, setBanSeconds] = useState(0);
	const [message, setMessage] = useState('');
	const [busy, setBusy] = useState(false);
	const [notice, setNotice] = useState<{text: string, severity: 'success' | 'error'} | null>(null);

	const refresh = () => {
		fetchLiveSession(true).then(l => {
			setLive(l);
			setLoaded(true);
		});
	};

	usePolling(refresh, 5000);

	const confirmKick = async () => {
		if (!toKick || toKick.refid === undefined) {
			return;
		}

		setBusy(true);
		const ok = await kickPlayer(toKick.refid, banSeconds || undefined);
		setBusy(false);
		setNotice(ok
			? {text: banSeconds ? `Kicked and banned ${toKick.name}` : `Kicked ${toKick.name}`, severity: 'success'}
			: {text: 'Could not kick the player', severity: 'error'});
		setToKick(null);
		refresh();
	};

	const send = async () => {
		const text = message.trim();
		if (!text) {
			return;
		}

		setBusy(true);
		const ok = await sendChat(text);
		setBusy(false);
		if (ok) {
			setMessage('');
		}

		setNotice(ok ? {text: 'Message sent', severity: 'success'} : {text: 'Could not send the message', severity: 'error'});
	};

	return (
		<Box sx={{mt: 3, textAlign: 'left'}}>
			<Typography variant="h6">Live Session</Typography>

			{loaded && !live
				&& <Alert severity="info" sx={{mt: 1}}>
					The game server isn&apos;t answering. Start it above, and make sure the HTTP API is enabled in server.cfg.
				</Alert>
			}

			{live && <>
				<Typography variant="body2" sx={{mt: 1, mb: 1}}>
					{live.stage ? `${prettyStage(live.stage)} · ` : ''}{live.players}{live.maxPlayers ? ` / ${live.maxPlayers}` : ''} players connected
				</Typography>

				{live.members.length > 0
					? <TableContainer component={Paper} variant="outlined">
						<Table size="small" aria-label="Connected players">
							<TableHead>
								<TableRow>
									<TableCell>Player</TableCell>
									<TableCell>Steam ID</TableCell>
									<TableCell align="right">Ping</TableCell>
									<TableCell align="right" />
								</TableRow>
							</TableHead>
							<TableBody>
								{live.members.map(m => (
									<TableRow key={m.refid ?? m.name}>
										<TableCell component="th" scope="row">{m.name}</TableCell>
										<TableCell>{m.steamid ?? ''}</TableCell>
										<TableCell align="right">{m.ping ?? ''}</TableCell>
										<TableCell align="right">
											<Button size="small" color="error" disabled={m.refid === undefined || m.refid === null}
												onClick={() => {
													setBanSeconds(0);
													setToKick(m);
												}}>
												Kick
											</Button>
										</TableCell>
									</TableRow>
								))}
							</TableBody>
						</Table>
					</TableContainer>
					: <Typography variant="body2" color="text.secondary">Nobody is connected.</Typography>
				}

				<Stack direction="row" spacing={2} sx={{mt: 2, alignItems: 'flex-end'}}>
					<TextField
						label="Message to everyone in the session"
						variant="standard"
						fullWidth
						value={message}
						disabled={busy}
						onChange={e => setMessage(e.target.value)}
						onKeyDown={e => {
							if (e.key === 'Enter') {
								send();
							}
						}}
						slotProps={{htmlInput: {maxLength: MAX_CHAT}}}
					/>
					<Button onClick={send} disabled={busy || message.trim() === ''}>Send</Button>
				</Stack>
			</>}

			<Dialog open={toKick !== null} onClose={() => setToKick(null)}>
				<DialogTitle>Kick {toKick?.name}?</DialogTitle>
				<DialogContent>
					<DialogContentText sx={{mb: 2}}>
						They are removed from the session now.
					</DialogContentText>
					<FormControl variant="standard" fullWidth>
						<InputLabel id="ban-length">What happens next</InputLabel>
						<Select labelId="ban-length" value={String(banSeconds)} onChange={e => setBanSeconds(Number(e.target.value))}>
							{BAN_OPTIONS.map(o => <MenuItem key={o.seconds} value={String(o.seconds)}>{o.label}</MenuItem>)}
						</Select>
					</FormControl>
				</DialogContent>
				<DialogActions>
					<Button onClick={() => setToKick(null)} disabled={busy}>Cancel</Button>
					<Button color="error" onClick={confirmKick} loading={busy}>{banSeconds ? 'Kick and ban' : 'Kick'}</Button>
				</DialogActions>
			</Dialog>

			<Snackbar open={notice !== null} autoHideDuration={3000} onClose={() => setNotice(null)}
				anchorOrigin={{vertical: 'bottom', horizontal: 'right'}}>
				{notice ? <Alert severity={notice.severity}>{notice.text}</Alert> : undefined}
			</Snackbar>
		</Box>
	);
};

export default LivePanel;
