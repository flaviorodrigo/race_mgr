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
import Paper from '@mui/material/Paper';
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

// Admin view of the running session: who is connected, kick a player, message the lobby.
const LivePanel = () => {
	const [live, setLive] = useState<ILiveSession | null>(null);
	const [loaded, setLoaded] = useState(false);
	const [toKick, setToKick] = useState<ILiveMember | null>(null);
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
		const ok = await kickPlayer(toKick.refid);
		setBusy(false);
		setNotice(ok ? {text: `Kicked ${toKick.name}`, severity: 'success'} : {text: 'Could not kick the player', severity: 'error'});
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
												onClick={() => setToKick(m)}>
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
					<DialogContentText>
						They are removed from the session now. They can rejoin unless you ban them in the game server.
					</DialogContentText>
				</DialogContent>
				<DialogActions>
					<Button onClick={() => setToKick(null)} disabled={busy}>Cancel</Button>
					<Button color="error" onClick={confirmKick} loading={busy}>Kick</Button>
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
