import React, {useEffect, useState} from 'react';

import {
	addBan, advanceSession, applyNextSession, changePassword, fetchAudit, fetchBanList, fetchServerConfig, removeBan, saveServerConfig,
	IAuditEvent, IBanEntry, INextSessionSettings, ServerConfigValues,
} from '../Live/adminApi';
import {fetchTrackData} from '../Leaderboard/utilities';
import RotationBuilder from './RotationBuilder';
import {ITrackData} from '../../interfaces/asm2Interfaces';

import Accordion from '@mui/material/Accordion';
import AccordionDetails from '@mui/material/AccordionDetails';
import AccordionSummary from '@mui/material/AccordionSummary';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogContentText from '@mui/material/DialogContentText';
import DialogTitle from '@mui/material/DialogTitle';
import FormControl from '@mui/material/FormControl';
import InputLabel from '@mui/material/InputLabel';
import MenuItem from '@mui/material/MenuItem';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import Select from '@mui/material/Select';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';

interface IFeedback {
	text: string,
	severity: 'success' | 'error',
}

const Feedback = ({feedback}: {feedback: IFeedback | null}) => (feedback
	? <Alert severity={feedback.severity} sx={{mt: 2}}>{feedback.text}</Alert>
	: null);

const toWhole = (value: string) => (/^-?\d{1,10}$/.test(value.trim()) ? Number(value) : NaN);

// ---------------------------------------------------------------- server.cfg

const SETTING_FIELDS: Array<{id: string, label: string, kind: 'text' | 'number' | 'bool', min?: number, max?: number}> = [
	{id: 'name', label: 'Server name', kind: 'text'},
	{id: 'maxPlayerCount', label: 'Players allowed to connect', kind: 'number', min: 1, max: 64},
	{id: 'sessionAttributes.MaxPlayers', label: 'Human drivers', kind: 'number', min: 1, max: 64},
	{id: 'sessionAttributes.GridSize', label: 'Grid size (with AI)', kind: 'number', min: 1, max: 64},
	{id: 'sessionAttributes.PracticeLength', label: 'Practice minutes', kind: 'number', min: 0, max: 600},
	{id: 'sessionAttributes.QualifyLength', label: 'Qualifying minutes', kind: 'number', min: 0, max: 600},
	{id: 'sessionAttributes.RaceLength', label: 'Race laps', kind: 'number', min: 1, max: 999},
	{id: 'sessionAttributes.OpponentDifficulty', label: 'AI difficulty (0-100)', kind: 'number', min: 0, max: 100},
	{id: 'secure', label: 'Check Steam authentication', kind: 'bool'},
	{id: 'allowEmptyJoin', label: 'Allow joining an empty server', kind: 'bool'},
];

const ServerSettingsPanel = () => {
	const [loaded, setLoaded] = useState<ServerConfigValues | null>(null);
	const [form, setForm] = useState<Record<string, string | boolean>>({});
	const [newPassword, setNewPassword] = useState('');
	const [clearPassword, setClearPassword] = useState(false);
	const [problem, setProblem] = useState('');
	const [feedback, setFeedback] = useState<IFeedback | null>(null);
	const [busy, setBusy] = useState(false);

	const load = async () => {
		const result = await fetchServerConfig();
		if (!result.ok || !result.data) {
			setProblem(result.message || 'Could not read server.cfg');
			return;
		}

		const values = result.data;
		setLoaded(values);
		setProblem('');
		setForm(Object.fromEntries(SETTING_FIELDS.filter(f => values[f.id] !== undefined).map(f => [f.id, f.kind === 'bool' ? Boolean(values[f.id]) : String(values[f.id])])));
	};

	useEffect(() => {
		load();
	}, []);

	// Only what you changed is sent
	const changes: ServerConfigValues = {};
	const errors: Array<string> = [];
	SETTING_FIELDS.forEach(f => {
		const current = form[f.id];
		if (loaded === null || current === undefined || String(current) === String(loaded[f.id])) {
			return;
		}

		if (f.kind === 'number') {
			const n = /^\d{1,3}$/.test(String(current).trim()) ? Number(current) : NaN;
			if (isNaN(n) || n < (f.min ?? 0) || n > (f.max ?? 999)) {
				errors.push(`${f.label} must be ${f.min} to ${f.max}`);
			} else {
				changes[f.id] = n;
			}
		} else if (f.kind === 'text') {
			if (String(current).length > 64) {
				errors.push(`${f.label} is limited to 64 characters`);
			} else {
				changes[f.id] = String(current);
			}
		} else {
			changes[f.id] = Boolean(current);
		}
	});
	if (clearPassword) {
		changes.password = '';
	} else if (newPassword !== '') {
		if (newPassword.length > 64) {
			errors.push('The password is limited to 64 characters');
		} else {
			changes.password = newPassword;
		}
	}

	const canSave = errors.length === 0 && Object.keys(changes).length > 0 && !busy;

	const save = async () => {
		setBusy(true);
		const result = await saveServerConfig(changes);
		setBusy(false);
		setFeedback({text: result.message, severity: result.ok ? 'success' : 'error'});
		if (result.ok) {
			setNewPassword('');
			setClearPassword(false);
			load();
		}
	};

	if (problem) {
		return <Alert severity="warning">{problem}</Alert>;
	}

	return (
		<Stack spacing={2} sx={{textAlign: 'left'}}>
			<Typography variant="body2">
				Edits a few values in the game server&apos;s <code>server.cfg</code>; the rest of the file, including comments, is left exactly as it is
				(the previous version is kept as <code>server.cfg.bak</code>). The game server reads this file when it starts, so <strong>restart it</strong> to apply changes.
			</Typography>
			<Stack direction="row" useFlexGap sx={{flexWrap: 'wrap', gap: 2}}>
				{SETTING_FIELDS.filter(f => form[f.id] !== undefined).map(f => (f.kind === 'bool'
					? <FormControlLabel key={f.id} label={f.label}
						control={<Checkbox checked={Boolean(form[f.id])} onChange={e => setForm({...form, [f.id]: e.target.checked})} />} />
					: <TextField key={f.id} label={f.label} variant="standard" value={String(form[f.id])}
						sx={{minWidth: f.kind === 'text' ? 280 : 170}}
						onChange={e => setForm({...form, [f.id]: e.target.value})}
						slotProps={{htmlInput: {maxLength: f.kind === 'text' ? 64 : 3}}} />
				))}
			</Stack>
			<Stack direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{alignItems: {sm: 'flex-end'}}}>
				<TextField label="Server password" variant="standard" type="password" autoComplete="new-password" sx={{minWidth: 280}}
					value={newPassword} disabled={clearPassword} onChange={e => setNewPassword(e.target.value)}
					helperText={loaded?.passwordSet ? 'A password is set. Type a new one to replace it.' : 'No password is set.'}
					slotProps={{htmlInput: {maxLength: 64}}} />
				{Boolean(loaded?.passwordSet) && <FormControlLabel label="Remove the password"
					control={<Checkbox checked={clearPassword} onChange={e => setClearPassword(e.target.checked)} />} />}
			</Stack>
			{errors.length > 0 && <Alert severity="warning">{errors.join('. ')}</Alert>}
			<Button variant="contained" sx={{alignSelf: 'flex-start'}} disabled={!canSave} onClick={save}>Save to server.cfg</Button>
			<Feedback feedback={feedback} />
		</Stack>
	);
};

// ---------------------------------------------------------------- next session

const NextSessionPanel = () => {
	const [tracks, setTracks] = useState<Array<ITrackData>>([]);
	const [trackId, setTrackId] = useState('');
	const [raceLength, setRaceLength] = useState('');
	const [practiceLength, setPracticeLength] = useState('');
	const [qualifyLength, setQualifyLength] = useState('');
	const [difficulty, setDifficulty] = useState('');
	const [feedback, setFeedback] = useState<IFeedback | null>(null);
	const [confirmAdvance, setConfirmAdvance] = useState(false);
	const [busy, setBusy] = useState(false);

	useEffect(() => {
		fetchTrackData().then(t => setTracks(Array.isArray(t) ? t : []));
	}, []);

	// Only the fields you filled in are sent; the rest of the session stays as it is
	const settings: INextSessionSettings = {};
	const problems: Array<string> = [];
	const take = (key: keyof INextSessionSettings, raw: string, label: string, range: [number, number]) => {
		const [min, max] = range;
		if (raw.trim() === '') {
			return;
		}

		const n = toWhole(raw);
		if (isNaN(n) || n < min || n > max) {
			problems.push(`${label} must be ${min} to ${max}`);
		} else {
			settings[key] = n;
		}
	};

	if (trackId !== '') {
		settings.TrackId = Number(trackId);
	}

	take('RaceLength', raceLength, 'Race laps', [1, 999]);
	take('PracticeLength', practiceLength, 'Practice minutes', [0, 600]);
	take('QualifyLength', qualifyLength, 'Qualifying minutes', [0, 600]);
	take('OpponentDifficulty', difficulty, 'AI difficulty', [0, 100]);
	const canApply = problems.length === 0 && Object.keys(settings).length > 0 && !busy;

	const apply = async () => {
		setBusy(true);
		const result = await applyNextSession(settings);
		setBusy(false);
		setFeedback({text: result.ok ? 'Sent. These apply when the next session starts.' : result.message, severity: result.ok ? 'success' : 'error'});
	};

	const advance = async () => {
		setConfirmAdvance(false);
		setBusy(true);
		const result = await advanceSession();
		setBusy(false);
		setFeedback({text: result.ok ? 'The session moved on to its next stage.' : result.message, severity: result.ok ? 'success' : 'error'});
	};

	return (
		<Stack spacing={2} sx={{textAlign: 'left'}}>
			<Typography variant="body2">
				Change a few things for the <strong>next</strong> session without restarting the server. Leave a field empty to keep it.
				If you use a rotation, it may set the track again when the next session starts.
			</Typography>
			<FormControl variant="standard" fullWidth>
				<InputLabel id="next-track">Track</InputLabel>
				<Select labelId="next-track" value={trackId} onChange={e => setTrackId(e.target.value)}>
					<MenuItem value=""><em>Keep as it is</em></MenuItem>
					{tracks.map(t => <MenuItem key={t.id} value={String(t.id)}>{t.name.replaceAll('_', ' ')}</MenuItem>)}
				</Select>
			</FormControl>
			<Stack direction={{xs: 'column', sm: 'row'}} spacing={2}>
				<TextField label="Race laps" variant="standard" value={raceLength} onChange={e => setRaceLength(e.target.value)} />
				<TextField label="Practice minutes" variant="standard" value={practiceLength} onChange={e => setPracticeLength(e.target.value)} />
				<TextField label="Qualifying minutes" variant="standard" value={qualifyLength} onChange={e => setQualifyLength(e.target.value)} />
				<TextField label="AI difficulty (0-100)" variant="standard" value={difficulty} onChange={e => setDifficulty(e.target.value)} />
			</Stack>
			{problems.length > 0 && <Alert severity="warning">{problems.join('. ')}</Alert>}
			<Stack direction="row" spacing={2}>
				<Button variant="contained" disabled={!canApply} onClick={apply}>Apply to next session</Button>
				<Button color="warning" disabled={busy} onClick={() => setConfirmAdvance(true)}>Advance to next stage</Button>
			</Stack>
			<Feedback feedback={feedback} />

			<Dialog open={confirmAdvance} onClose={() => setConfirmAdvance(false)}>
				<DialogTitle>Advance the session?</DialogTitle>
				<DialogContent>
					<DialogContentText>
						The running session jumps to its next stage now (for example from qualifying to the race). Players are moved with it.
					</DialogContentText>
				</DialogContent>
				<DialogActions>
					<Button onClick={() => setConfirmAdvance(false)}>Cancel</Button>
					<Button color="warning" onClick={advance}>Advance</Button>
				</DialogActions>
			</Dialog>
		</Stack>
	);
};

// ---------------------------------------------------------------- ban list

const BanListPanel = () => {
	const [entries, setEntries] = useState<Array<IBanEntry> | null>(null);
	const [problem, setProblem] = useState('');
	const [steamId, setSteamId] = useState('');
	const [note, setNote] = useState('');
	const [feedback, setFeedback] = useState<IFeedback | null>(null);
	const [toRemove, setToRemove] = useState<IBanEntry | null>(null);

	const load = async () => {
		const result = await fetchBanList();
		setEntries(result.ok ? result.data ?? [] : null);
		setProblem(result.ok ? '' : result.message);
	};

	useEffect(() => {
		load();
	}, []);

	const add = async () => {
		const result = await addBan(steamId.trim(), note);
		setFeedback({text: result.message, severity: result.ok ? 'success' : 'error'});
		if (result.ok) {
			setSteamId('');
			setNote('');
			load();
		}
	};

	const remove = async () => {
		if (!toRemove) {
			return;
		}

		const result = await removeBan(toRemove.steamId);
		setToRemove(null);
		setFeedback({text: result.message, severity: result.ok ? 'success' : 'error'});
		load();
	};

	return (
		<Stack spacing={2} sx={{textAlign: 'left'}}>
			<Typography variant="body2">
				Players on this list can&apos;t join. It&apos;s the game server&apos;s ban file; the server reads it when it loads its configuration, so restart it for a change to be sure to apply.
			</Typography>
			{problem && <Alert severity="warning">{problem}</Alert>}
			{entries && <>
				{entries.length > 0
					? <Table size="small" aria-label="Ban list">
						<TableHead>
							<TableRow>
								<TableCell>Steam ID</TableCell>
								<TableCell>Note</TableCell>
								<TableCell />
							</TableRow>
						</TableHead>
						<TableBody>
							{entries.map(e => (
								<TableRow key={e.steamId}>
									<TableCell>{e.steamId}</TableCell>
									<TableCell>{e.note}</TableCell>
									<TableCell align="right"><Button size="small" onClick={() => setToRemove(e)}>Remove</Button></TableCell>
								</TableRow>
							))}
						</TableBody>
					</Table>
					: <Typography variant="body2" color="text.secondary">Nobody is banned.</Typography>
				}
				<Stack direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{alignItems: {sm: 'flex-end'}}}>
					<TextField label="Steam ID (17 digits)" variant="standard" value={steamId} onChange={e => setSteamId(e.target.value)}
						error={steamId !== '' && !/^\d{17}$/.test(steamId.trim())} slotProps={{htmlInput: {maxLength: 17}}} />
					<TextField label="Note (optional)" variant="standard" fullWidth value={note} onChange={e => setNote(e.target.value)}
						slotProps={{htmlInput: {maxLength: 100}}} />
					<Button disabled={!/^\d{17}$/.test(steamId.trim())} onClick={add}>Ban</Button>
				</Stack>
			</>}
			<Feedback feedback={feedback} />

			<Dialog open={toRemove !== null} onClose={() => setToRemove(null)}>
				<DialogTitle>Remove {toRemove?.steamId} from the ban list?</DialogTitle>
				<DialogActions>
					<Button onClick={() => setToRemove(null)}>Cancel</Button>
					<Button color="error" onClick={remove}>Remove</Button>
				</DialogActions>
			</Dialog>
		</Stack>
	);
};

// ---------------------------------------------------------------- activity log

const AuditPanel = () => {
	const [events, setEvents] = useState<Array<IAuditEvent> | null>(null);

	const load = async () => {
		const result = await fetchAudit();
		setEvents(result.ok ? result.data ?? [] : []);
	};

	useEffect(() => {
		load();
	}, []);

	return (
		<Stack spacing={2} sx={{textAlign: 'left'}}>
			<Typography variant="body2">The latest admin actions: logins, kicks, settings, scoring and password changes. Kept for 180 days.</Typography>
			{events && events.length > 0
				? <Table size="small" aria-label="Activity log">
					<TableHead>
						<TableRow>
							<TableCell>When</TableCell>
							<TableCell>Who</TableCell>
							<TableCell>What</TableCell>
							<TableCell>Details</TableCell>
						</TableRow>
					</TableHead>
					<TableBody>
						{events.map(e => (
							<TableRow key={e.id}>
								<TableCell sx={{whiteSpace: 'nowrap'}}>{new Date(e.time).toLocaleString()}</TableCell>
								<TableCell>{e.actor}</TableCell>
								<TableCell>{e.action}</TableCell>
								<TableCell>{e.detail}</TableCell>
							</TableRow>
						))}
					</TableBody>
				</Table>
				: <Typography variant="body2" color="text.secondary">{events ? 'Nothing recorded yet.' : 'Loading...'}</Typography>
			}
			<Button sx={{alignSelf: 'flex-start'}} onClick={load}>Refresh</Button>
		</Stack>
	);
};

// ---------------------------------------------------------------- account

const MIN_PASSWORD = 12;

const AccountPanel = () => {
	const [current, setCurrent] = useState('');
	const [next, setNext] = useState('');
	const [repeat, setRepeat] = useState('');
	const [feedback, setFeedback] = useState<IFeedback | null>(null);
	const [busy, setBusy] = useState(false);

	const valid = current !== '' && next.length >= MIN_PASSWORD && next === repeat;

	const submit = async () => {
		setBusy(true);
		const result = await changePassword(current, next);
		setBusy(false);
		setFeedback({
			text: result.ok ? 'Password changed. Every other signed-in device was signed out.' : result.message,
			severity: result.ok ? 'success' : 'error',
		});
		if (result.ok) {
			setCurrent('');
			setNext('');
			setRepeat('');
		}
	};

	return (
		<Stack spacing={2} sx={{textAlign: 'left', maxWidth: 420}}>
			<Typography variant="body2">
				Choose a new admin password (at least {MIN_PASSWORD} characters). It replaces the one in the server&apos;s settings.
			</Typography>
			<TextField label="Current password" type="password" variant="standard" autoComplete="current-password" value={current} onChange={e => setCurrent(e.target.value)} />
			<TextField label="New password" type="password" variant="standard" autoComplete="new-password" value={next} onChange={e => setNext(e.target.value)}
				error={next !== '' && next.length < MIN_PASSWORD} helperText={next !== '' && next.length < MIN_PASSWORD ? `At least ${MIN_PASSWORD} characters` : ''} />
			<TextField label="Repeat new password" type="password" variant="standard" autoComplete="new-password" value={repeat} onChange={e => setRepeat(e.target.value)}
				error={repeat !== '' && repeat !== next} helperText={repeat !== '' && repeat !== next ? 'The passwords differ' : ''} />
			<Button variant="contained" sx={{alignSelf: 'flex-start'}} disabled={!valid || busy} onClick={submit}>Change password</Button>
			<Feedback feedback={feedback} />
		</Stack>
	);
};

// ---------------------------------------------------------------- the group

const sections = [
	{id: 'rotation', title: 'Rotation builder', body: <RotationBuilder />},
	{id: 'config', title: 'Server settings (server.cfg)', body: <ServerSettingsPanel />},
	{id: 'next', title: 'Next session settings', body: <NextSessionPanel />},
	{id: 'bans', title: 'Ban list', body: <BanListPanel />},
	{id: 'audit', title: 'Activity log', body: <AuditPanel />},
	{id: 'account', title: 'Account', body: <AccountPanel />},
];

// Collapsed by default, and each section only loads its data when it is opened
const AdminTools = () => {
	const [open, setOpen] = useState<string | false>(false);

	return (
		<div style={{marginTop: 24}}>
			{sections.map(s => (
				<Accordion key={s.id} expanded={open === s.id} onChange={(_event, isOpen) => setOpen(isOpen ? s.id : false)}
					disableGutters slotProps={{transition: {unmountOnExit: true}}}>
					<AccordionSummary expandIcon={<ExpandMoreIcon />} aria-controls={`${s.id}-content`} id={`${s.id}-header`}>
						<Typography>{s.title}</Typography>
					</AccordionSummary>
					<AccordionDetails id={`${s.id}-content`}>{s.body}</AccordionDetails>
				</Accordion>
			))}
		</div>
	);
};

export default AdminTools;
