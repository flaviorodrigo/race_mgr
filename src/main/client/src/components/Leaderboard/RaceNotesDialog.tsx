import React, {useState} from 'react';

import {saveRacePenalties, saveRaceVideo} from '../Live/adminApi';
import {IPenalty} from '../../utils/penalties';

import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import FormControlLabel from '@mui/material/FormControlLabel';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';

interface IProps {
	raceId: string,
	drivers: Array<string>,
	penalties: Array<IPenalty>,
	videoUrl: string,
	onClose: () => void,
	onSaved: () => void,
}

const MAX_SECONDS = 3600;
const VIDEO_PATTERN = /^https:\/\/(www\.|m\.)?(youtube\.com|youtu\.be|twitch\.tv)\//;

// Admin dialog on a race page: time penalties, disqualifications and a video link
const RaceNotesDialog = ({raceId, drivers, penalties, videoUrl, onClose, onSaved}: IProps) => {
	const [rows, setRows] = useState<Array<IPenalty>>(penalties.map(p => ({...p})));
	const [video, setVideo] = useState(videoUrl);
	const [busy, setBusy] = useState(false);
	const [error, setError] = useState('');

	const update = (index: number, change: Partial<IPenalty>) => setRows(rows.map((r, i) => (i === index ? {...r, ...change} : r)));
	const rowValid = (r: IPenalty) => drivers.includes(r.driver) && (r.disqualify || (Number.isInteger(r.seconds) && r.seconds >= 1 && r.seconds <= MAX_SECONDS));
	const videoValid = video.trim() === '' || VIDEO_PATTERN.test(video.trim());
	const valid = videoValid && rows.every(rowValid);

	const save = async () => {
		setBusy(true);
		setError('');
		const penaltyResult = await saveRacePenalties(raceId, rows.map(r => ({...r, seconds: r.disqualify ? 0 : r.seconds})));
		const videoResult = penaltyResult.ok ? await saveRaceVideo(raceId, video.trim()) : penaltyResult;
		setBusy(false);
		if (!penaltyResult.ok || !videoResult.ok) {
			setError(videoResult.message || 'Could not save');
			return;
		}

		onSaved();
	};

	return (
		<Dialog open onClose={busy ? undefined : onClose} fullWidth maxWidth="md">
			<DialogTitle>Penalties and video</DialogTitle>
			<DialogContent>
				<Stack spacing={2} sx={{pt: 1}}>
					<Typography variant="body2">
						A time penalty is added to the driver&apos;s race time and the result is re-ordered; a disqualification keeps the driver in the
						result with no points. The game server&apos;s own record is not changed.
					</Typography>

					{rows.map((r, i) => (
						<Stack key={i} direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{alignItems: {sm: 'flex-end'}}} aria-label={`Penalty ${i + 1}`}>
							<TextField select label="Driver" variant="standard" value={r.driver} sx={{minWidth: 180}} onChange={e => update(i, {driver: e.target.value})}>
								{drivers.map(d => <MenuItem key={d} value={d}>{d}</MenuItem>)}
							</TextField>
							<TextField label="Seconds" variant="standard" type="number" value={r.disqualify || r.seconds === 0 ? '' : r.seconds} disabled={r.disqualify} sx={{width: 130}}
								onChange={e => update(i, {seconds: Number(e.target.value)})}
								error={!r.disqualify && !rowValid(r)} slotProps={{htmlInput: {min: 1, max: MAX_SECONDS}}} />
							<FormControlLabel control={<Checkbox checked={r.disqualify} onChange={e => update(i, {disqualify: e.target.checked})} />} label="Disqualify" />
							<TextField label="Reason (optional)" variant="standard" fullWidth value={r.note} onChange={e => update(i, {note: e.target.value})}
								slotProps={{htmlInput: {maxLength: 120}}} />
							<Button color="error" onClick={() => setRows(rows.filter((_, n) => n !== i))}>Remove</Button>
						</Stack>
					))}
					<Button sx={{alignSelf: 'flex-start'}} disabled={drivers.length === 0}
						onClick={() => setRows([...rows, {driver: drivers[0], seconds: 5, disqualify: false, note: ''}])}>
						Add penalty
					</Button>

					<TextField label="Video link (YouTube or Twitch, optional)" variant="standard" fullWidth value={video} onChange={e => setVideo(e.target.value)}
						error={!videoValid} helperText={videoValid ? '' : 'An https link to youtube.com, youtu.be or twitch.tv'} slotProps={{htmlInput: {maxLength: 200}}} />
					{error && <Alert severity="error">{error}</Alert>}
				</Stack>
			</DialogContent>
			<DialogActions>
				<Button onClick={onClose} disabled={busy}>Cancel</Button>
				<Button variant="contained" onClick={save} disabled={!valid} loading={busy}>Save</Button>
			</DialogActions>
		</Dialog>
	);
};

export default RaceNotesDialog;
