import React, {useEffect, useRef, useState} from 'react';

import {
	applyNextSession, fetchGameList, fetchRotationConfig, fetchSavedRotations, fetchSessionSchema, saveCustomRotation, IGameListItem, ISavedRotation,
} from '../Live/adminApi';
import {configFromModel, modelFromConfig} from '../../utils/rotation';
import {parseServerJson} from '../../utils/serverJson';
import {ISchema, toNextSessionValues} from '../../utils/sessionSettings';
import SettingsEditor from './SettingsEditor';

import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import FormControl from '@mui/material/FormControl';
import FormControlLabel from '@mui/material/FormControlLabel';
import IconButton from '@mui/material/IconButton';
import InputLabel from '@mui/material/InputLabel';
import MenuItem from '@mui/material/MenuItem';
import Select from '@mui/material/Select';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward';
import DeleteIcon from '@mui/icons-material/Delete';

type Round = Record<string, unknown>;

const SAFE_NAME = /^[A-Za-z0-9_,.()\- ]{1,200}$/;
const MAX_ROUNDS = 200;

// A starting point for a new rotation; everything can be changed below
const STARTING_SETUP: Record<string, unknown> = {
	PracticeLength: 0, QualifyLength: 5, RaceLength: 5,
	Flags: 'ALLOW_CUSTOM_VEHICLE_SETUP,ABS_ALLOWED,SC_ALLOWED,TCS_ALLOWED,FILL_SESSION_WITH_AI,AUTO_START_ENGINE',
	DamageType: 'VISUAL_ONLY', TireWearType: 'OFF', FuelUsageType: 'OFF', PenaltiesType: 'NONE', AllowedViews: 'Any',
	QualifyWeatherSlots: 1, QualifyWeatherSlot1: 'Clear', RaceWeatherSlots: 1, RaceWeatherSlot1: 'Clear', RaceDateProgression: 30,
};

const name = (item?: IGameListItem) => (item ? item.translated_name ?? item.name.replaceAll('_', ' ') : '');

// Builds a rotation: the default setup every round starts from (every setting the game server offers), then the
// rounds in order, each with its track and anything it changes.
const RotationBuilder = () => {
	const [schema, setSchema] = useState<ISchema | null>(null);
	const [tracks, setTracks] = useState<Array<IGameListItem>>([]);
	const [classes, setClasses] = useState<Array<IGameListItem>>([]);
	const [vehicles, setVehicles] = useState<Array<IGameListItem>>([]);
	const [saved, setSaved] = useState<Array<ISavedRotation>>([]);

	const [rotationName, setRotationName] = useState('');
	const [persistIndex, setPersistIndex] = useState(true);
	const [defaults, setDefaults] = useState<Record<string, unknown>>(STARTING_SETUP);
	const [rounds, setRounds] = useState<Array<Round>>([{TrackId: ''}]);
	const [editing, setEditing] = useState<number | null>(null);
	const [message, setMessage] = useState<{text: string, severity: 'success' | 'error' | 'info'} | null>(null);
	const [busy, setBusy] = useState(false);
	const fileInput = useRef<HTMLInputElement>(null);

	useEffect(() => {
		fetchSessionSchema().then(r => setSchema(r.ok && r.data ? r.data : null));
		fetchGameList('tracks').then(setTracks);
		fetchGameList('vehicle_classes').then(setClasses);
		fetchGameList('vehicles').then(setVehicles);
		fetchSavedRotations().then(setSaved);
	}, []);

	const problems: Array<string> = [];
	if (!/^[A-Za-z0-9 _.()-]{1,64}$/.test(rotationName)) {
		problems.push('Give the rotation a name (letters, numbers, spaces, - _ . ( ))');
	}

	if (rounds.length === 0 || rounds.some(r => r.TrackId === '' || r.TrackId === undefined)) {
		problems.push('Every round needs a track');
	}

	const buildConfig = () => configFromModel({persistIndex, defaults, rounds});

	const loadConfig = (config: any, fallbackName = '') => {
		const model = modelFromConfig(config);
		if (!model || model.rounds.length > MAX_ROUNDS) {
			setMessage({text: 'That is not a rotation Race Manager can read.', severity: 'error'});
			return;
		}

		if (model.rounds.some(r => typeof r.TrackId === 'string' && !SAFE_NAME.test(r.TrackId))) {
			setMessage({text: 'Some rounds in that rotation have no valid track.', severity: 'error'});
			return;
		}

		setPersistIndex(model.persistIndex);
		setDefaults(model.defaults);
		setRounds(model.rounds);
		if (fallbackName) {
			setRotationName(fallbackName);
		}

		setMessage({text: `Loaded ${model.rounds.length} rounds. Change anything, then save it under a new name.`, severity: 'info'});
	};

	const loadSaved = async (id: string) => {
		const config = await fetchRotationConfig(id);
		const rotation = saved.find(s => s.id === id);
		loadConfig(config, rotation ? `${rotation.name} copy`.slice(0, 64) : '');
	};

	const importFile = async (file?: File) => {
		if (!file) {
			return;
		}

		if (file.size > 200_000) {
			setMessage({text: 'That file is too large to be a rotation.', severity: 'error'});
			return;
		}

		try {
			// The game server's files have comments and bare keys, so they are read the way the server reads them
			const parsed: any = parseServerJson(await file.text());
			loadConfig(parsed?.config ?? parsed, file.name.replace(/\.json$/i, '').replace(/[^A-Za-z0-9 _.()-]/g, '').slice(0, 64));
		} catch (error) {
			setMessage({text: `That file can't be read: ${(error as Error).message}.`, severity: 'error'});
		}
	};

	// The same file format the game server reads, so it can be shared and dropped in as is
	const exportFile = () => {
		const config = buildConfig();
		// eslint-disable-next-line camelcase
		const fileConfig = {persist_index: config.persist_index, default: config.defaultConfig, rotation: config.rotation};
		const blob = new Blob([JSON.stringify({version: 7, config: fileConfig}, null, 2)], {type: 'application/json'});
		const url = URL.createObjectURL(blob);
		const link = document.createElement('a');
		link.href = url;
		link.download = `${rotationName.trim() || 'rotation'}.sms_rotate_default_config.json`;
		document.body.appendChild(link);
		link.click();
		link.remove();
		URL.revokeObjectURL(url);
	};

	const save = async () => {
		setBusy(true);
		const result = await saveCustomRotation(rotationName.trim(), buildConfig());
		setBusy(false);
		setMessage({
			text: result.ok ? 'Saved. Select it under Rotation and use Update Server to make it the active one.' : result.message,
			severity: result.ok ? 'success' : 'error',
		});
		if (result.ok) {
			fetchSavedRotations().then(setSaved);
		}
	};

	const updateRound = (index: number, round: Round) => setRounds(rounds.map((r, i) => (i === index ? round : r)));
	const setOrRemove = (round: Round, key: string, value: unknown) => {
		const copy = {...round};
		if (value === '' || value === undefined) {
			delete copy[key];
		} else {
			copy[key] = value;
		}

		return copy;
	};

	const move = (index: number, by: number) => {
		const target = index + by;
		if (target < 0 || target >= rounds.length) {
			return;
		}

		const copy = [...rounds];
		[copy[index], copy[target]] = [copy[target], copy[index]];
		setRounds(copy);
	};

	const known = (items: Array<IGameListItem>, value: unknown) => items.find(i => i.name === value || String(i.id) === String(value));
	const listSelect = ({label, items, value, emptyLabel, onPick, width}: {label: string, items: Array<IGameListItem>, value: unknown, emptyLabel: string, onPick: (v: string) => void, width: number}) => {
		const current = known(items, value);
		const raw = value !== undefined && value !== '' && !current ? String(value) : null;
		return (
			<FormControl variant="standard" sx={{minWidth: width}}>
				<InputLabel>{label}</InputLabel>
				<Select value={current ? current.name : (raw ?? '')} onChange={e => onPick(e.target.value)}>
					<MenuItem value=""><em>{emptyLabel}</em></MenuItem>
					{raw && <MenuItem value={raw}>{raw} (as in the file)</MenuItem>}
					{items.map(i => <MenuItem key={i.name} value={i.name}>{name(i)}</MenuItem>)}
				</Select>
			</FormControl>
		);
	};

	// Sends one round (the default setup with the round's own changes) to the next session, without a restart
	const raceNext = async (index: number) => {
		if (!schema) {
			setMessage({text: 'The game server isn\'t answering, so nothing can be sent to it.', severity: 'error'});
			return;
		}

		const {values, skipped} = toNextSessionValues({...defaults, ...rounds[index]}, schema, {tracks, classes, vehicles});
		if (skipped.length > 0) {
			setMessage({text: `Round ${index + 1} can't be sent as it is: ${skipped.join(', ')} could not be turned into the game server's values.`, severity: 'error'});
			return;
		}

		setBusy(true);
		const result = await applyNextSession(values);
		setBusy(false);
		setMessage({
			text: result.ok ? `Round ${index + 1} sent: it is the setup of the next session (a rotation may change it again then).` : result.message,
			severity: result.ok ? 'success' : 'error',
		});
	};

	const changedIn = (round: Round) => Object.keys(round).filter(k => k !== 'TrackId' && k !== 'VehicleClassId').length;
	const editorLists = {tracks, classes, vehicles};

	return (
		<Stack spacing={2} sx={{textAlign: 'left'}}>
			<Typography variant="body2">
				A rotation is a default setup that every round starts from, then the rounds in order, each with its track and anything it
				changes. Every setting the game server offers is here. Saving keeps it with your other rotations; it only goes live when you
				select it and use Update Server.
			</Typography>

			<Stack direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{alignItems: {sm: 'flex-end'}}}>
				<FormControl variant="standard" sx={{minWidth: 240}}>
					<InputLabel id="rb-load">Start from a saved rotation</InputLabel>
					<Select labelId="rb-load" value="" onChange={e => loadSaved(e.target.value)}>
						{saved.map(s => <MenuItem key={s.id} value={s.id}>{s.name}</MenuItem>)}
					</Select>
				</FormControl>
				<Button onClick={() => fileInput.current?.click()}>Import file</Button>
				<input ref={fileInput} type="file" accept=".json,.cfg,.txt,application/json" hidden onChange={e => {
					importFile(e.target.files?.[0]);
					e.target.value = '';
				}} />
			</Stack>

			<Stack direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{alignItems: {sm: 'flex-end'}}}>
				<TextField label="Rotation name" variant="standard" value={rotationName} onChange={e => setRotationName(e.target.value)} slotProps={{htmlInput: {maxLength: 64}}} />
				<FormControlLabel control={<Checkbox checked={persistIndex} onChange={e => setPersistIndex(e.target.checked)} />}
					label="Carry on from the same round after a restart" />
			</Stack>

			<Box>
				<Typography variant="subtitle1">Default setup</Typography>
				<Typography variant="body2" color="text.secondary" sx={{mb: 1}}>
					Every round starts from these. A setting that is not set is left to the game. Hover a setting for the game server&apos;s description.
				</Typography>
				<SettingsEditor idPrefix="default" schema={schema} values={defaults} onChange={setDefaults} {...editorLists} />
			</Box>

			<Typography variant="subtitle1">Rounds</Typography>
			{rounds.map((round, index) => (
				<Stack key={index} direction="row" useFlexGap sx={{flexWrap: 'wrap', gap: 1.5, alignItems: 'flex-end'}} aria-label={`Round ${index + 1}`}>
					<Typography sx={{width: 28}}>{index + 1}</Typography>
					{listSelect({label: 'Track', items: tracks, value: round.TrackId, emptyLabel: 'Choose a track', onPick: v => updateRound(index, {...round, TrackId: v}), width: 230})}
					{listSelect({label: 'Cars', items: classes, value: round.VehicleClassId, emptyLabel: 'As in the default setup', onPick: v => updateRound(index, setOrRemove(round, 'VehicleClassId', v)), width: 200})}
					<Button onClick={() => setEditing(index)}>Round settings{changedIn(round) > 0 ? ` (${changedIn(round)})` : ''}</Button>
					<Tooltip title="Make this round the setup of the next session now, without a restart">
						<span><Button disabled={busy || !round.TrackId} onClick={() => raceNext(index)}>Race next</Button></span>
					</Tooltip>
					<Tooltip title="Move up"><span><IconButton size="small" aria-label="Move up" disabled={index === 0} onClick={() => move(index, -1)}><ArrowUpwardIcon fontSize="small" /></IconButton></span></Tooltip>
					<Tooltip title="Move down"><span><IconButton size="small" aria-label="Move down" disabled={index === rounds.length - 1} onClick={() => move(index, 1)}><ArrowDownwardIcon fontSize="small" /></IconButton></span></Tooltip>
					<Tooltip title="Remove round"><span><IconButton size="small" aria-label="Remove round" disabled={rounds.length === 1} onClick={() => setRounds(rounds.filter((_r, i) => i !== index))}><DeleteIcon fontSize="small" /></IconButton></span></Tooltip>
				</Stack>
			))}

			<Stack direction="row" spacing={2}>
				<Button disabled={rounds.length >= MAX_ROUNDS} onClick={() => setRounds([...rounds, {TrackId: ''}])}>Add round</Button>
				<Button disabled={problems.length > 0} onClick={exportFile}>Export file</Button>
				<Button variant="contained" disabled={problems.length > 0 || busy} onClick={save}>Save rotation</Button>
			</Stack>
			{problems.length > 0 && <Alert severity="info">{problems.join('. ')}</Alert>}
			{message && <Alert severity={message.severity}>{message.text}</Alert>}

			<Dialog open={editing !== null} onClose={() => setEditing(null)} fullWidth maxWidth="lg">
				<DialogTitle>
					Round {editing === null ? '' : editing + 1} settings
					{editing !== null && rounds[editing] && ` · ${name(known(tracks, rounds[editing].TrackId)) || String(rounds[editing].TrackId ?? '')}`}
				</DialogTitle>
				<DialogContent>
					<Typography variant="body2" color="text.secondary" sx={{mb: 1}}>
						Only what this round changes from the default setup. A setting that is not set here comes from the default setup.
					</Typography>
					{editing !== null && rounds[editing] && <SettingsEditor idPrefix={`round${editing}`} schema={schema} values={rounds[editing]}
						onChange={values => updateRound(editing, {...values, TrackId: rounds[editing].TrackId})}
						exclude={['TrackId', 'VehicleClassId']} {...editorLists} />}
				</DialogContent>
				<DialogActions>
					<Button onClick={() => setEditing(null)}>Done</Button>
				</DialogActions>
			</Dialog>
		</Stack>
	);
};

export default RotationBuilder;
