import React, {useEffect, useRef, useState} from 'react';
import {useDispatch} from 'react-redux';

import {getRotations} from '../../state/rotations';

import {
	applyNextSession, deleteRotation, fetchGameList, fetchRotationConfig, fetchSavedRotations, fetchSessionSchema, saveCustomRotation, savePreset, updateCustomRotation,
	IGameListItem, ISavedRotation,
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
// In lists: the game's display name with the id rotation files use ("Formula V8 Gen2 · F-V8_Gen2"), alphabetical
const optionLabel = (item: IGameListItem) => (item.translated_name ? `${item.translated_name} · ${item.name}` : item.name.replaceAll('_', ' '));
const byLabel = (items: Array<IGameListItem>) => [...items].sort((a, b) => optionLabel(a).localeCompare(optionLabel(b)));

// What stops a rotation from being saved
const problemsOf = (rotationName: string, rounds: Array<Round>) => [
	/^[A-Za-z0-9 _.()-]{1,64}$/.test(rotationName) ? null : 'Give the rotation a name (letters, numbers, spaces, - _ . ( ))',
	rounds.length > 0 && rounds.every(r => r.TrackId !== '' && r.TrackId !== undefined) ? null : 'Every round needs a track',
].filter((p): p is string => p !== null);

// A new rotation is saved; an opened one can be changed in place, copied or deleted
const SaveButtons = ({editing, disabled, busy, onSave, onSaveChanges, onDelete}: {editing: boolean, disabled: boolean, busy: boolean,
	onSave: () => void, onSaveChanges: () => void, onDelete: () => void}) => (editing
	? <>
		<Button variant="contained" disabled={disabled} onClick={onSaveChanges}>Save changes</Button>
		<Button disabled={disabled} onClick={onSave}>Save as new</Button>
		<Button color="error" disabled={busy} onClick={onDelete}>Delete rotation</Button>
	</>
	: <Button variant="contained" disabled={disabled} onClick={onSave}>Save rotation</Button>);

// Builds a rotation: the default setup every round starts from (every setting the game server offers), then the
// rounds in order, each with its track and anything it changes.
const RotationBuilder = () => {
	const [schema, setSchema] = useState<ISchema | null>(null);
	const [tracks, setTracks] = useState<Array<IGameListItem>>([]);
	const [classes, setClasses] = useState<Array<IGameListItem>>([]);
	const [vehicles, setVehicles] = useState<Array<IGameListItem>>([]);
	const [saved, setSaved] = useState<Array<ISavedRotation>>([]);

	const [rotationName, setRotationName] = useState('');
	// The saved rotation being edited (null for a new one or an imported file)
	const [editingId, setEditingId] = useState<string | null>(null);
	const [confirmDelete, setConfirmDelete] = useState(false);
	const [persistIndex, setPersistIndex] = useState(true);
	const [defaults, setDefaults] = useState<Record<string, unknown>>(STARTING_SETUP);
	const [rounds, setRounds] = useState<Array<Round>>([{TrackId: ''}]);
	const [editing, setEditing] = useState<number | null>(null);
	const [presetFor, setPresetFor] = useState<{index: number, name: string} | null>(null);
	const [message, setMessage] = useState<{text: string, severity: 'success' | 'error' | 'info'} | null>(null);
	// What loading a rotation did, shown right under the load buttons (the save message is at the bottom)
	const [loadMessage, setLoadMessage] = useState<{text: string, severity: 'success' | 'error' | 'info'} | null>(null);
	const [busy, setBusy] = useState(false);
	const fileInput = useRef<HTMLInputElement>(null);
	const dispatch = useDispatch();

	useEffect(() => {
		fetchSessionSchema().then(r => setSchema(r.ok && r.data ? r.data : null));
		fetchGameList('tracks').then(setTracks);
		fetchGameList('vehicle_classes').then(setClasses);
		fetchGameList('vehicles').then(setVehicles);
		fetchSavedRotations().then(setSaved);
	}, []);

	const problems = problemsOf(rotationName, rounds);

	const buildConfig = () => configFromModel({persistIndex, defaults, rounds});

	const loadConfig = (config: any, fallbackName = '') => {
		const model = modelFromConfig(config);
		if (!model || model.rounds.length > MAX_ROUNDS) {
			setLoadMessage({text: 'That is not a rotation Race Manager can read.', severity: 'error'});
			return false;
		}

		if (model.rounds.some(r => typeof r.TrackId === 'string' && !SAFE_NAME.test(r.TrackId))) {
			setLoadMessage({text: 'Some rounds in that rotation have no valid track.', severity: 'error'});
			return false;
		}

		setPersistIndex(model.persistIndex);
		setDefaults(model.defaults);
		setRounds(model.rounds);
		if (fallbackName) {
			setRotationName(fallbackName);
		}

		const trackNames = model.rounds.map(r => name(known(tracks, r.TrackId)) || String(r.TrackId)).join(', ');
		setLoadMessage({
			text: `Loaded ${model.rounds.length} round${model.rounds.length === 1 ? '' : 's'} (${trackNames}) and a default setup with ${Object.keys(model.defaults).length} settings. `
				+ 'Check them below, change anything, then save it under a name.',
			severity: 'success',
		});
		// A loaded file is a new rotation until it's saved; loadSaved marks what it opened
		setEditingId(null);
		return true;
	};

	const loadSaved = async (id: string) => {
		const config = await fetchRotationConfig(id);
		const rotation = saved.find(s => s.id === id);
		if (loadConfig(config, rotation?.name ?? '')) {
			setEditingId(id);
		}
	};

	const importFile = async (file?: File) => {
		if (!file) {
			return;
		}

		if (file.size > 200_000) {
			setLoadMessage({text: 'That file is too large to be a rotation.', severity: 'error'});
			return;
		}

		try {
			// The game server's files have comments and bare keys, so they are read the way the server reads them
			const parsed: any = parseServerJson(await file.text());
			loadConfig(parsed?.config ?? parsed, file.name.replace(/\.json$/i, '').replace(/[^A-Za-z0-9 _.()-]/g, '').slice(0, 64));
		} catch (error) {
			setLoadMessage({text: `That file can't be read: ${(error as Error).message}.`, severity: 'error'});
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

	const afterSave = () => {
		fetchSavedRotations().then(setSaved);
		// The Rotation dropdown at the top of the tab lists it straight away
		dispatch(getRotations());
	};

	// A new rotation, or a copy of the one being edited
	const save = async () => {
		setBusy(true);
		const result = await saveCustomRotation(rotationName.trim(), buildConfig());
		setBusy(false);
		setMessage({
			text: result.ok ? 'Saved as a new rotation. Select it under Rotation and use Set active (or Update Server) to use it.' : result.message,
			severity: result.ok ? 'success' : 'error',
		});
		if (result.ok) {
			setEditingId((result.data as {id?: string} | undefined)?.id ?? null);
			afterSave();
		}
	};

	// The rotation that was opened, changed in place
	const saveChanges = async () => {
		if (!editingId) {
			return;
		}

		setBusy(true);
		const result = await updateCustomRotation(editingId, rotationName.trim(), buildConfig());
		setBusy(false);
		setMessage({text: result.message || (result.ok ? 'Saved.' : 'Could not save'), severity: result.ok ? 'success' : 'error'});
		if (result.ok) {
			afterSave();
		}
	};

	const remove = async () => {
		setConfirmDelete(false);
		if (!editingId) {
			return;
		}

		setBusy(true);
		const result = await deleteRotation(editingId);
		setBusy(false);
		setMessage({text: result.ok ? `Deleted "${rotationName.trim()}". Races recorded under it keep their results.` : result.message, severity: result.ok ? 'success' : 'error'});
		if (result.ok) {
			setEditingId(null);
			afterSave();
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

	// Exact name or id first; otherwise ignoring case, spaces and underscores ("Londrina Long" is Londrina_long)
	const loose = (v: unknown) => String(v).toLowerCase().replace(/[^a-z0-9]/g, '');
	const known = (items: Array<IGameListItem>, value: unknown) => items.find(i => i.name === value || String(i.id ?? i.value) === String(value))
		?? (value === undefined || value === '' ? undefined : items.find(i => loose(i.name) === loose(value)));
	const listSelect = ({label, items, value, emptyLabel, onPick, width}: {label: string, items: Array<IGameListItem>, value: unknown, emptyLabel: string, onPick: (v: string) => void, width: number}) => {
		const current = known(items, value);
		const raw = value !== undefined && value !== '' && !current ? String(value) : null;
		return (
			<FormControl variant="standard" sx={{minWidth: width}}>
				<InputLabel>{label}</InputLabel>
				<Select value={current ? current.name : (raw ?? '')} onChange={e => onPick(e.target.value)}>
					<MenuItem value=""><em>{emptyLabel}</em></MenuItem>
					{raw && <MenuItem value={raw}>{raw} (as in the file)</MenuItem>}
					{byLabel(items).map(i => <MenuItem key={i.name} value={i.name}>{optionLabel(i)}</MenuItem>)}
				</Select>
			</FormControl>
		);
	};

	// A round as the game server's API takes it: the default setup with the round's own changes, as numbers
	const roundValues = (index: number): Record<string, number> | null => {
		if (!schema) {
			setMessage({text: 'The game server isn\'t answering, so its settings can\'t be turned into the values it takes.', severity: 'error'});
			return null;
		}

		const {values, skipped} = toNextSessionValues({...defaults, ...rounds[index]}, schema, {tracks, classes, vehicles});
		if (skipped.length > 0) {
			setMessage({text: `Round ${index + 1}: ${skipped.join(', ')} could not be turned into the game server's values.`, severity: 'error'});
			return null;
		}

		return values;
	};

	// Sends one round to the next session, without a restart
	const raceNext = async (index: number) => {
		const values = roundValues(index);
		if (!values) {
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

	// "Season 1 · 2 · Interlagos": the rotation, the round and its track, within a preset name's 40 characters
	const presetNameFor = (index: number) => {
		const track = name(known(tracks, rounds[index].TrackId)) || String(rounds[index].TrackId ?? '');
		return [rotationName.trim() || 'Round', String(index + 1), track].join(' · ').replace(/\p{Cc}/gu, ' ').slice(0, 40).trim();
	};

	const saveRoundPreset = async (index: number, presetName: string) => {
		const values = roundValues(index);
		if (!values) {
			return false;
		}

		const result = await savePreset(presetName, values);
		if (!result.ok) {
			setMessage({text: `Round ${index + 1}: ${result.message}`, severity: 'error'});
		}

		return result.ok;
	};

	const saveAllPresets = async () => {
		setBusy(true);
		let saved = 0;
		for (let i = 0; i < rounds.length; i++) {
			// eslint-disable-next-line no-await-in-loop -- one at a time, so a full preset list stops it cleanly
			if (await saveRoundPreset(i, presetNameFor(i))) {
				saved++;
			} else {
				break;
			}
		}

		setBusy(false);
		if (saved === rounds.length) {
			setMessage({text: `Saved ${saved} presets, one per round. Apply them under Next session settings.`, severity: 'success'});
		}
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
			{loadMessage && <Alert severity={loadMessage.severity} onClose={() => setLoadMessage(null)}>{loadMessage.text}</Alert>}

			<Stack direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{alignItems: {sm: 'flex-end'}}}>
				{editingId && <Typography variant="body2" color="text.secondary" sx={{alignSelf: 'center'}}>Editing a saved rotation</Typography>}
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
					<Tooltip title="Save this round (with the default setup) as a preset, to apply it any time under Next session settings">
						<span><Button disabled={busy || !round.TrackId} onClick={() => setPresetFor({index, name: presetNameFor(index)})}>Save as preset</Button></span>
					</Tooltip>
					<Tooltip title="Move up"><span><IconButton size="small" aria-label="Move up" disabled={index === 0} onClick={() => move(index, -1)}><ArrowUpwardIcon fontSize="small" /></IconButton></span></Tooltip>
					<Tooltip title="Move down"><span><IconButton size="small" aria-label="Move down" disabled={index === rounds.length - 1} onClick={() => move(index, 1)}><ArrowDownwardIcon fontSize="small" /></IconButton></span></Tooltip>
					<Tooltip title="Remove round"><span><IconButton size="small" aria-label="Remove round" disabled={rounds.length === 1} onClick={() => setRounds(rounds.filter((_r, i) => i !== index))}><DeleteIcon fontSize="small" /></IconButton></span></Tooltip>
				</Stack>
			))}

			<Stack direction="row" spacing={2}>
				<Button disabled={rounds.length >= MAX_ROUNDS} onClick={() => setRounds([...rounds, {TrackId: ''}])}>Add round</Button>
				<Button disabled={problems.length > 0} onClick={exportFile}>Export file</Button>
				<Button disabled={problems.length > 0 || busy} onClick={saveAllPresets}>Save every round as a preset</Button>
				<SaveButtons editing={Boolean(editingId)} disabled={problems.length > 0 || busy} busy={busy}
					onSave={save} onSaveChanges={saveChanges} onDelete={() => setConfirmDelete(true)} />
			</Stack>
			{problems.length > 0 && <Alert severity="info">{problems.join('. ')}</Alert>}
			{message && <Alert severity={message.severity}>{message.text}</Alert>}

			<Dialog open={confirmDelete} onClose={() => setConfirmDelete(false)}>
				<DialogTitle>Delete the rotation &quot;{rotationName.trim()}&quot;?</DialogTitle>
				<DialogContent>
					<Typography variant="body2">Races already recorded under it keep their results. The active rotation can&apos;t be deleted.</Typography>
				</DialogContent>
				<DialogActions>
					<Button onClick={() => setConfirmDelete(false)}>Cancel</Button>
					<Button color="error" onClick={remove}>Delete</Button>
				</DialogActions>
			</Dialog>

			<Dialog open={presetFor !== null} onClose={() => setPresetFor(null)}>
				<DialogTitle>Save round {presetFor ? presetFor.index + 1 : ''} as a preset</DialogTitle>
				<DialogContent>
					<Typography variant="body2" color="text.secondary" sx={{mb: 1}}>
						The default setup with this round&apos;s changes. A preset with the same name is replaced.
					</Typography>
					<TextField label="Preset name" variant="standard" fullWidth value={presetFor?.name ?? ''} slotProps={{htmlInput: {maxLength: 40}}}
						onChange={e => setPresetFor(presetFor && {...presetFor, name: e.target.value})} />
				</DialogContent>
				<DialogActions>
					<Button onClick={() => setPresetFor(null)}>Cancel</Button>
					<Button variant="contained" disabled={!presetFor || presetFor.name.trim() === '' || busy} onClick={async () => {
						if (!presetFor) {
							return;
						}

						setBusy(true);
						const ok = await saveRoundPreset(presetFor.index, presetFor.name.trim());
						setBusy(false);
						if (ok) {
							setMessage({text: `Saved the preset "${presetFor.name.trim()}". Apply it under Next session settings.`, severity: 'success'});
						}

						setPresetFor(null);
					}}>Save preset</Button>
				</DialogActions>
			</Dialog>

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
