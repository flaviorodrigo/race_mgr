import React, {useEffect, useState} from 'react';

import {
	addBan, addToWhitelist, advanceSession, applyNextSession, applyPreset, createAdminUser, deleteAdminUser, deletePreset, fetchAdminUsers, fetchGameList,
	fetchPresets, fetchSessionSchema, savePreset,
	updateAdminUser, changePassword, fetchAudit, fetchBanList, fetchGameLog, fetchServerConfig, fetchWhitelist,
	removeBan, removeFromWhitelist, saveServerConfig,
	IAdminUser, IApiResult, IAuditEvent, IBanEntry, IGameListItem, ILogEvent, INextSessionSettings, ISessionPreset, IWhitelist, ServerConfigValues,
} from '../Live/adminApi';
import RotationBuilder from './RotationBuilder';
import PerformancePanel from './PerformancePanel';
import {atLeast, generatePassword, Role, roleLabel} from '../../utils/roles';
import {ISchema, toNextSessionValues} from '../../utils/sessionSettings';
import SettingsEditor from './SettingsEditor';
import {useRole} from '../Live/useRole';

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

// "Brands Hatch GP · 10 laps · qualifying 15 min · AI 90 · 3 more settings"
const describePreset = (settings: INextSessionSettings, tracks: Array<IGameListItem>) => {
	const named: Record<string, (v: number) => string> = {
		TrackId: v => tracks.find(t => Number(t.id) === v)?.name.replaceAll('_', ' ') ?? `track ${v}`,
		RaceLength: v => `${v} laps`,
		PracticeLength: v => `practice ${v} min`,
		QualifyLength: v => `qualifying ${v} min`,
		OpponentDifficulty: v => `AI ${v}`,
	};
	const parts = Object.keys(named).filter(k => settings[k] !== undefined).map(k => named[k](settings[k]));
	const others = Object.keys(settings).filter(k => !(k in named)).length;
	return [...parts, others > 0 ? `${others} more setting${others === 1 ? '' : 's'}` : null].filter(Boolean).join(' · ');
};

// The next session's setup, with every setting the game server offers. Applied through the game server's API, no
// restart; only what is filled in is sent.
const NextSessionPanel = () => {
	const [schema, setSchema] = useState<ISchema | null>(null);
	const [tracks, setTracks] = useState<Array<IGameListItem>>([]);
	const [classes, setClasses] = useState<Array<IGameListItem>>([]);
	const [vehicles, setVehicles] = useState<Array<IGameListItem>>([]);
	const [values, setValues] = useState<Record<string, unknown>>({});
	const [feedback, setFeedback] = useState<IFeedback | null>(null);
	const [confirmAdvance, setConfirmAdvance] = useState(false);
	const [busy, setBusy] = useState(false);
	const [presets, setPresets] = useState<Array<ISessionPreset>>([]);
	const [presetName, setPresetName] = useState('');
	const [toDelete, setToDelete] = useState<ISessionPreset | null>(null);
	// Stewards run presets; saving and deleting them is for admins
	const canEditPresets = atLeast(useRole().role, 'ADMIN');

	const loadPresets = async () => {
		const result = await fetchPresets();
		setPresets(result.ok ? result.data ?? [] : []);
	};

	useEffect(() => {
		fetchSessionSchema().then(r => setSchema(r.ok && r.data ? r.data : null));
		fetchGameList('tracks').then(setTracks);
		fetchGameList('vehicle_classes').then(setClasses);
		fetchGameList('vehicles').then(setVehicles);
		loadPresets();
	}, []);

	const converted = schema ? toNextSessionValues(values, schema, {tracks, classes, vehicles}) : null;
	const canApply = Boolean(converted) && Object.keys(converted!.values).length > 0 && converted!.skipped.length === 0 && !busy;

	const apply = async () => {
		setBusy(true);
		const result = await applyNextSession(converted!.values);
		setBusy(false);
		setFeedback({text: result.ok ? 'Sent. These apply when the next session starts.' : result.message, severity: result.ok ? 'success' : 'error'});
	};

	const saveAsPreset = async () => {
		setBusy(true);
		const result = await savePreset(presetName.trim(), converted!.values);
		setBusy(false);
		setFeedback({text: result.ok ? `Preset "${presetName.trim()}" saved.` : result.message, severity: result.ok ? 'success' : 'error'});
		if (result.ok) {
			setPresetName('');
			loadPresets();
		}
	};

	const usePreset = async (preset: ISessionPreset) => {
		setBusy(true);
		const result = await applyPreset(preset.id);
		setBusy(false);
		setFeedback({text: result.ok ? `"${preset.name}" sent. It applies when the next session starts.` : result.message, severity: result.ok ? 'success' : 'error'});
	};

	const removePreset = async () => {
		if (!toDelete) {
			return;
		}

		const result = await deletePreset(toDelete.id);
		setToDelete(null);
		setFeedback({text: result.message, severity: result.ok ? 'success' : 'error'});
		loadPresets();
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
				Change the <strong>next</strong> session without restarting the server: any setting the game server offers. Only what you
				set is sent; the rest stays as it is. If you use a rotation, it may set the track again when the next session starts.
			</Typography>
			<SettingsEditor idPrefix="next" schema={schema} values={values} onChange={setValues} tracks={tracks} classes={classes} vehicles={vehicles} />
			{converted && converted.skipped.length > 0 && <Alert severity="warning">
				These can&apos;t be sent as they are: {converted.skipped.join(', ')}. Pick them from the lists, or clear them.
			</Alert>}
			<Stack direction="row" spacing={2}>
				<Button variant="contained" disabled={!canApply} onClick={apply}>Apply to next session</Button>
				<Button disabled={busy || Object.keys(values).length === 0} onClick={() => setValues({})}>Clear</Button>
				<Button color="warning" disabled={busy} onClick={() => setConfirmAdvance(true)}>Advance to next stage</Button>
			</Stack>
			{canEditPresets && <Stack direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{alignItems: {sm: 'flex-end'}}}>
				<TextField label="Save these settings as a preset" variant="standard" fullWidth value={presetName} onChange={e => setPresetName(e.target.value)}
					helperText="Saving under an existing name replaces that preset" slotProps={{htmlInput: {maxLength: 40}}} />
				<Button disabled={!canApply || presetName.trim() === ''} onClick={saveAsPreset}>Save preset</Button>
			</Stack>}
			{presets.length > 0 && <>
				<Typography variant="subtitle2">Saved presets</Typography>
				<Table size="small" aria-label="Presets">
					<TableBody>
						{presets.map(p => (
							<TableRow key={p.id}>
								<TableCell component="th" scope="row">{p.name}</TableCell>
								<TableCell>{describePreset(p.settings, tracks)}</TableCell>
								<TableCell align="right" sx={{whiteSpace: 'nowrap'}}>
									<Button size="small" disabled={busy} onClick={() => usePreset(p)}>Apply</Button>
									<Button size="small" onClick={() => {
										setValues({...p.settings});
										setPresetName(p.name);
									}}>Edit</Button>
									{canEditPresets && <Button size="small" color="error" onClick={() => setToDelete(p)}>Delete</Button>}
								</TableCell>
							</TableRow>
						))}
					</TableBody>
				</Table>
			</>}
			<Feedback feedback={feedback} />

			<Dialog open={toDelete !== null} onClose={() => setToDelete(null)}>
				<DialogTitle>Delete the preset &quot;{toDelete?.name}&quot;?</DialogTitle>
				<DialogActions>
					<Button onClick={() => setToDelete(null)}>Cancel</Button>
					<Button color="error" onClick={removePreset}>Delete</Button>
				</DialogActions>
			</Dialog>

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

// ---------------------------------------------------------------- ban list and whitelist

interface IListConfig {
	ariaLabel: string,
	intro: string,
	empty: string,
	addLabel: string,
	removeTitle: (steamId: string) => string,
	load: () => Promise<IApiResult<IWhitelist>>,
	add: (steamId: string, note: string) => Promise<IApiResult>,
	remove: (steamId: string) => Promise<IApiResult>,
	// Extra explanation under the intro, from what the list holds
	status?: (list: IWhitelist) => React.ReactNode,
}

// Both lists are the game server's own files of Steam IDs, edited the same way
const SteamIdListPanel = ({config}: {config: IListConfig}) => {
	const [list, setList] = useState<IWhitelist | null>(null);
	const [problem, setProblem] = useState('');
	const [steamId, setSteamId] = useState('');
	const [note, setNote] = useState('');
	const [feedback, setFeedback] = useState<IFeedback | null>(null);
	const [toRemove, setToRemove] = useState<IBanEntry | null>(null);

	const load = async () => {
		const result = await config.load();
		setList(result.ok ? result.data ?? null : null);
		setProblem(result.ok ? '' : result.message);
	};

	useEffect(() => {
		load();
	}, []);

	const add = async () => {
		const result = await config.add(steamId.trim(), note);
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

		const result = await config.remove(toRemove.steamId);
		setToRemove(null);
		setFeedback({text: result.message, severity: result.ok ? 'success' : 'error'});
		load();
	};

	return (
		<Stack spacing={2} sx={{textAlign: 'left'}}>
			<Typography variant="body2">{config.intro}</Typography>
			{problem && <Alert severity="warning">{problem}</Alert>}
			{list && <>
				{config.status?.(list)}
				{list.entries.length > 0
					? <Table size="small" aria-label={config.ariaLabel}>
						<TableHead>
							<TableRow>
								<TableCell>Steam ID</TableCell>
								<TableCell>Note</TableCell>
								<TableCell />
							</TableRow>
						</TableHead>
						<TableBody>
							{list.entries.map(e => (
								<TableRow key={e.steamId}>
									<TableCell>{e.steamId}</TableCell>
									<TableCell>{e.note}</TableCell>
									<TableCell align="right"><Button size="small" onClick={() => setToRemove(e)}>Remove</Button></TableCell>
								</TableRow>
							))}
						</TableBody>
					</Table>
					: <Typography variant="body2" color="text.secondary">{config.empty}</Typography>
				}
				<Stack direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{alignItems: {sm: 'flex-end'}}}>
					<TextField label="Steam ID (17 digits)" variant="standard" value={steamId} onChange={e => setSteamId(e.target.value)}
						error={steamId !== '' && !/^\d{17}$/.test(steamId.trim())} slotProps={{htmlInput: {maxLength: 17}}} />
					<TextField label="Note (optional)" variant="standard" fullWidth value={note} onChange={e => setNote(e.target.value)}
						slotProps={{htmlInput: {maxLength: 100}}} />
					<Button disabled={!/^\d{17}$/.test(steamId.trim())} onClick={add}>{config.addLabel}</Button>
				</Stack>
			</>}
			<Feedback feedback={feedback} />

			<Dialog open={toRemove !== null} onClose={() => setToRemove(null)}>
				<DialogTitle>{toRemove ? config.removeTitle(toRemove.steamId) : ''}</DialogTitle>
				<DialogActions>
					<Button onClick={() => setToRemove(null)}>Cancel</Button>
					<Button color="error" onClick={remove}>Remove</Button>
				</DialogActions>
			</Dialog>
		</Stack>
	);
};

const banListConfig: IListConfig = {
	ariaLabel: 'Ban list',
	intro: 'Players on this list can\'t join. It\'s the game server\'s ban file; the server reads it when it loads its configuration, so restart it for a change to be sure to apply.',
	empty: 'Nobody is banned.',
	addLabel: 'Ban',
	removeTitle: id => `Remove ${id} from the ban list?`,
	async load() {
		const result = await fetchBanList();
		return {...result, data: result.data ? {entries: result.data, active: true} : undefined};
	},
	add: addBan,
	remove: removeBan,
};

const whitelistConfig: IListConfig = {
	ariaLabel: 'Whitelist',
	intro: 'For a closed server: when the game server uses its whitelist, only the players listed here can join (the ban list is then ignored). It\'s the game server\'s whitelist.cfg; restart the server for a change to be sure to apply.',
	empty: 'Nobody is listed.',
	addLabel: 'Allow',
	removeTitle: id => `Remove ${id} from the whitelist?`,
	load: fetchWhitelist,
	add: addToWhitelist,
	remove: removeFromWhitelist,
	status(list) {
		if (!list.active) {
			return (
				<Alert severity="info" sx={{textAlign: 'left'}}>
					The game server isn&apos;t using this list yet, so anybody can join. To use it, add the line{' '}
					<code>{'whiteList : { "whitelist" : "whitelist.cfg" }'}</code> to server.cfg and restart the server.
				</Alert>
			);
		}

		return list.entries.length > 0
			? <Alert severity="warning" sx={{textAlign: 'left'}}>The whitelist is in use: only the {list.entries.length} listed player{list.entries.length === 1 ? '' : 's'} can join.</Alert>
			: <Alert severity="info" sx={{textAlign: 'left'}}>The whitelist is in use but empty, so everybody can join. Add players to close the server to everyone else.</Alert>;
	},
};

const BanListPanel = () => <SteamIdListPanel config={banListConfig} />;
const WhitelistPanel = () => <SteamIdListPanel config={whitelistConfig} />;

// ---------------------------------------------------------------- game server log

const LOG_SIZES = [50, 100, 250, 500];

const GameLogPanel = () => {
	const [events, setEvents] = useState<Array<ILogEvent> | null>(null);
	const [problem, setProblem] = useState('');
	const [count, setCount] = useState(100);
	const [filter, setFilter] = useState('');

	const load = async () => {
		const result = await fetchGameLog(count);
		setEvents(result.ok ? result.data ?? [] : null);
		setProblem(result.ok ? '' : 'The game server isn\'t answering. Start it, and make sure its HTTP API is enabled.');
	};

	useEffect(() => {
		load();
	}, [count]);

	const shown = (events ?? []).filter(e => {
		const text = `${e.name} ${Object.entries(e.attributes).map(([k, v]) => `${k}=${v}`).join(' ')}`.toLowerCase();
		return text.includes(filter.trim().toLowerCase());
	});

	return (
		<Stack spacing={2} sx={{textAlign: 'left'}}>
			<Typography variant="body2">What the game server itself recorded recently (joins, leaves, stage changes, chat, results), newest first. It only keeps a limited amount.</Typography>
			{problem && <Alert severity="warning">{problem}</Alert>}
			<Stack direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{alignItems: {sm: 'flex-end'}}}>
				<TextField label="Filter" variant="standard" value={filter} onChange={e => setFilter(e.target.value)} />
				<FormControl variant="standard" sx={{minWidth: 140}}>
					<InputLabel id="log-size">Show latest</InputLabel>
					<Select labelId="log-size" value={String(count)} onChange={e => setCount(Number(e.target.value))}>
						{LOG_SIZES.map(n => <MenuItem key={n} value={String(n)}>{n} events</MenuItem>)}
					</Select>
				</FormControl>
				<Button onClick={load}>Refresh</Button>
			</Stack>
			{events && (shown.length > 0
				? <Table size="small" aria-label="Game server log">
					<TableHead>
						<TableRow>
							<TableCell>When</TableCell>
							<TableCell>Event</TableCell>
							<TableCell>Details</TableCell>
						</TableRow>
					</TableHead>
					<TableBody>
						{shown.map(e => (
							<TableRow key={e.index ?? `${e.time}-${e.name}`}>
								<TableCell sx={{whiteSpace: 'nowrap'}}>{e.time ? new Date(e.time * 1000).toLocaleString() : ''}</TableCell>
								<TableCell>{e.name}</TableCell>
								<TableCell sx={{wordBreak: 'break-word'}}>
									{[e.refid !== undefined && e.refid !== null ? `player ${e.refid}` : '', ...Object.entries(e.attributes).map(([k, v]) => `${k}: ${v}`)].filter(Boolean).join(' · ')}
								</TableCell>
							</TableRow>
						))}
					</TableBody>
				</Table>
				: <Typography variant="body2" color="text.secondary">{events.length === 0 ? 'The game server has no events yet.' : 'No event matches the filter.'}</Typography>)
			}
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

// ---------------------------------------------------------------- other admins (owner only)

const ASSIGNABLE: Array<{role: Role, help: string}> = [
	{role: 'ADMIN', help: 'Everything except managing other admins'},
	{role: 'STEWARD', help: 'Race day only: kick, chat, next session and presets, penalties and video, live view and log'},
];

const AdminsPanel = () => {
	const [users, setUsers] = useState<Array<IAdminUser> | null>(null);
	const [name, setName] = useState('');
	const [password, setPassword] = useState('');
	const [role, setRole] = useState<Role>('STEWARD');
	const [feedback, setFeedback] = useState<IFeedback | null>(null);
	const [reset, setReset] = useState<{user: string, password: string} | null>(null);
	const [toRemove, setToRemove] = useState<string | null>(null);
	const [shown, setShown] = useState<{user: string, password: string} | null>(null);

	const load = async () => {
		const result = await fetchAdminUsers();
		setUsers(result.ok ? result.data ?? [] : null);
	};

	useEffect(() => {
		load();
	}, []);

	const validName = /^[A-Za-z0-9_.-]{3,32}$/.test(name.trim());
	const validPassword = password.length >= MIN_PASSWORD && password.length <= 128;

	const add = async () => {
		const result = await createAdminUser(name.trim(), password, role);
		setFeedback({text: result.message, severity: result.ok ? 'success' : 'error'});
		if (result.ok) {
			// The password is shown once, so the owner can hand it over
			setShown({user: name.trim().toLowerCase(), password});
			setName('');
			setPassword('');
			load();
		}
	};

	const change = async (user: string, newRole: Role) => {
		const result = await updateAdminUser(user, {role: newRole});
		setFeedback({text: result.ok ? `${user} is now ${roleLabel(newRole)}` : result.message, severity: result.ok ? 'success' : 'error'});
		load();
	};

	const doReset = async () => {
		if (!reset) {
			return;
		}

		const result = await updateAdminUser(reset.user, {password: reset.password});
		setFeedback({text: result.ok ? `New password set for ${reset.user}. They were signed out everywhere.` : result.message, severity: result.ok ? 'success' : 'error'});
		if (result.ok) {
			setShown(reset);
		}

		setReset(null);
	};

	const remove = async () => {
		if (!toRemove) {
			return;
		}

		const result = await deleteAdminUser(toRemove);
		setToRemove(null);
		setFeedback({text: result.message, severity: result.ok ? 'success' : 'error'});
		load();
	};

	return (
		<Stack spacing={2} sx={{textAlign: 'left'}}>
			<Typography variant="body2">
				Give friends their own sign-in instead of sharing yours. Everything they do is in the activity log under their name.
				You (the owner) are the only one who can manage this list.
			</Typography>
			{ASSIGNABLE.map(a => <Typography key={a.role} variant="body2"><strong>{roleLabel(a.role)}:</strong> {a.help}.</Typography>)}
			{users && <Table size="small" aria-label="Admins">
				<TableHead>
					<TableRow>
						<TableCell>User</TableCell>
						<TableCell>Role</TableCell>
						<TableCell />
					</TableRow>
				</TableHead>
				<TableBody>
					{users.map(u => (
						<TableRow key={u.username}>
							<TableCell component="th" scope="row">{u.username}</TableCell>
							<TableCell>
								{u.role === 'OWNER'
									? roleLabel(u.role)
									: <Select variant="standard" size="small" value={u.role} inputProps={{'aria-label': `Role of ${u.username}`}}
										onChange={e => change(u.username, e.target.value as Role)}>
										{ASSIGNABLE.map(a => <MenuItem key={a.role} value={a.role}>{roleLabel(a.role)}</MenuItem>)}
									</Select>}
							</TableCell>
							<TableCell align="right" sx={{whiteSpace: 'nowrap'}}>
								{u.role !== 'OWNER' && <>
									<Button size="small" onClick={() => setReset({user: u.username, password: ''})}>Reset password</Button>
									<Button size="small" color="error" onClick={() => setToRemove(u.username)}>Remove</Button>
								</>}
							</TableCell>
						</TableRow>
					))}
				</TableBody>
			</Table>}

			<Typography variant="subtitle2">Add someone</Typography>
			<Stack direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{alignItems: {sm: 'flex-end'}}}>
				<TextField label="User name" variant="standard" value={name} onChange={e => setName(e.target.value)}
					error={name !== '' && !validName} helperText="3 to 32 letters, digits, . - _" />
				<TextField label="Password" variant="standard" value={password} onChange={e => setPassword(e.target.value)} autoComplete="off"
					error={password !== '' && !validPassword} helperText={`At least ${MIN_PASSWORD} characters`} />
				<Button onClick={() => setPassword(generatePassword())}>Generate</Button>
				<FormControl variant="standard" sx={{minWidth: 120}}>
					<InputLabel id="new-admin-role">Role</InputLabel>
					<Select labelId="new-admin-role" value={role} onChange={e => setRole(e.target.value as Role)}>
						{ASSIGNABLE.map(a => <MenuItem key={a.role} value={a.role}>{roleLabel(a.role)}</MenuItem>)}
					</Select>
				</FormControl>
				<Button variant="contained" disabled={!validName || !validPassword} onClick={add}>Add</Button>
			</Stack>
			{shown && <Alert severity="info" onClose={() => setShown(null)}>
				Give {shown.user} this password; it is not shown again: <code>{shown.password}</code>. They can change it themselves under Account.
			</Alert>}
			<Feedback feedback={feedback} />

			<Dialog open={reset !== null} onClose={() => setReset(null)}>
				<DialogTitle>New password for {reset?.user}</DialogTitle>
				<DialogContent>
					<DialogContentText sx={{mb: 1}}>They are signed out everywhere and use this password from now on.</DialogContentText>
					<Stack direction="row" spacing={2} sx={{alignItems: 'flex-end'}}>
						<TextField label="New password" variant="standard" fullWidth autoComplete="off" value={reset?.password ?? ''}
							onChange={e => setReset(reset && {...reset, password: e.target.value})} helperText={`At least ${MIN_PASSWORD} characters`} />
						<Button onClick={() => setReset(reset && {...reset, password: generatePassword()})}>Generate</Button>
					</Stack>
				</DialogContent>
				<DialogActions>
					<Button onClick={() => setReset(null)}>Cancel</Button>
					<Button variant="contained" disabled={(reset?.password.length ?? 0) < MIN_PASSWORD} onClick={doReset}>Set password</Button>
				</DialogActions>
			</Dialog>

			<Dialog open={toRemove !== null} onClose={() => setToRemove(null)}>
				<DialogTitle>Remove {toRemove}?</DialogTitle>
				<DialogContent>
					<DialogContentText>They are signed out at once and can&apos;t sign in again.</DialogContentText>
				</DialogContent>
				<DialogActions>
					<Button onClick={() => setToRemove(null)}>Cancel</Button>
					<Button color="error" onClick={remove}>Remove</Button>
				</DialogActions>
			</Dialog>
		</Stack>
	);
};

// ---------------------------------------------------------------- the group

// The least role that can use each tool; the server enforces the same rules
const sections: Array<{id: string, title: string, needs: Role, body: React.ReactNode}> = [
	{id: 'bop', title: 'Balance of performance', needs: 'ADMIN', body: <PerformancePanel />},
	{id: 'rotation', title: 'Rotation builder', needs: 'ADMIN', body: <RotationBuilder />},
	{id: 'config', title: 'Server settings (server.cfg)', needs: 'ADMIN', body: <ServerSettingsPanel />},
	{id: 'next', title: 'Next session settings', needs: 'STEWARD', body: <NextSessionPanel />},
	{id: 'bans', title: 'Ban list', needs: 'ADMIN', body: <BanListPanel />},
	{id: 'whitelist', title: 'Whitelist', needs: 'ADMIN', body: <WhitelistPanel />},
	{id: 'log', title: 'Game server log', needs: 'STEWARD', body: <GameLogPanel />},
	{id: 'audit', title: 'Activity log', needs: 'ADMIN', body: <AuditPanel />},
	{id: 'admins', title: 'Admins', needs: 'OWNER', body: <AdminsPanel />},
	{id: 'account', title: 'Account', needs: 'STEWARD', body: <AccountPanel />},
];

// Collapsed by default, and each section only loads its data when it is opened
const AdminTools = () => {
	const [open, setOpen] = useState<string | false>(false);
	const {role} = useRole();

	return (
		<div style={{marginTop: 24}}>
			{sections.filter(s => atLeast(role, s.needs)).map(s => (
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
