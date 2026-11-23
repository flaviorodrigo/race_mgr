import React, {useEffect, useRef, useState} from 'react';

import {fetchGameList, fetchRotationConfig, fetchSavedRotations, saveCustomRotation, IGameListItem, ISavedRotation} from '../Live/adminApi';

import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import FormControl from '@mui/material/FormControl';
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

interface IEntry {
	track: string,
	qualifyWeather1: string,
	qualifyWeather2: string,
	raceWeather1: string,
	raceWeather2: string,
}

const CLEAR = 'Clear';
const emptyEntry = (): IEntry => ({track: '', qualifyWeather1: CLEAR, qualifyWeather2: CLEAR, raceWeather1: CLEAR, raceWeather2: CLEAR});
const SAFE_NAME = /^[A-Za-z0-9_,.()\- ]{1,200}$/;
const MAX_ENTRIES = 200;

// Everything the builder doesn't edit (flags, damage, tyre wear...) is carried through from the rotation you start from
const DEFAULTS = {
	Flags: 'ALLOW_CUSTOM_VEHICLE_SETUP,ABS_ALLOWED,SC_ALLOWED,TCS_ALLOWED,FILL_SESSION_WITH_AI,AUTO_START_ENGINE',
	DamageType: 'VISUAL_ONLY', TireWearType: 'OFF', FuelUsageType: 'OFF', PenaltiesType: 'NONE', AllowedViews: 'Any',
	QualifyWeatherSlots: 2, RaceWeatherSlots: 2, QualifyWeatherSlot1: CLEAR, QualifyWeatherSlot2: CLEAR,
	RaceWeatherSlot1: CLEAR, RaceWeatherSlot2: CLEAR, RaceDateProgression: 30,
};

const pretty = (name: string) => name.replaceAll('_', ' ');

// Builds a rotation by hand: pick the cars once, then the tracks and weather for each round, in order.
const RotationBuilder = () => {
	const [tracks, setTracks] = useState<Array<IGameListItem>>([]);
	const [classes, setClasses] = useState<Array<IGameListItem>>([]);
	const [weather, setWeather] = useState<Array<IGameListItem>>([]);
	const [saved, setSaved] = useState<Array<ISavedRotation>>([]);

	const [name, setName] = useState('');
	const [vehicleClass, setVehicleClass] = useState('');
	const [raceLaps, setRaceLaps] = useState('5');
	const [qualifyMinutes, setQualifyMinutes] = useState('5');
	const [practiceMinutes, setPracticeMinutes] = useState('0');
	const [entries, setEntries] = useState<Array<IEntry>>([emptyEntry()]);
	const [base, setBase] = useState<Record<string, unknown>>(DEFAULTS);
	const [message, setMessage] = useState<{text: string, severity: 'success' | 'error' | 'info'} | null>(null);
	const [busy, setBusy] = useState(false);
	const fileInput = useRef<HTMLInputElement>(null);

	useEffect(() => {
		fetchGameList('tracks').then(setTracks);
		fetchGameList('vehicle_classes').then(setClasses);
		fetchGameList('weather').then(setWeather);
		fetchSavedRotations().then(setSaved);
	}, []);

	const weatherOptions = weather.length > 0 ? weather.map(w => w.name) : [CLEAR];
	const whole = (v: string) => (/^\d{1,3}$/.test(v.trim()) ? Number(v) : NaN);

	const problems: Array<string> = [];
	if (!/^[A-Za-z0-9 _.()-]{1,64}$/.test(name)) {
		problems.push('Give the rotation a name (letters, numbers, spaces, - _ . ( ))');
	}

	if (!vehicleClass) {
		problems.push('Choose a vehicle class');
	}

	if (isNaN(whole(raceLaps)) || whole(raceLaps) < 1) {
		problems.push('Race laps must be 1 or more');
	}

	if (isNaN(whole(qualifyMinutes)) || isNaN(whole(practiceMinutes))) {
		problems.push('Practice and qualifying minutes must be whole numbers');
	}

	if (entries.length === 0 || entries.some(e => !e.track)) {
		problems.push('Every round needs a track');
	}

	// Persist_index is the game server's own spelling
	/* eslint-disable camelcase */
	const buildConfig = () => ({
		persist_index: true,
		defaultConfig: {
			...base,
			PracticeLength: whole(practiceMinutes),
			QualifyLength: whole(qualifyMinutes),
			RaceLength: whole(raceLaps),
			VehicleClassId: vehicleClass,
		},
		rotation: entries.map(e => ({
			TrackId: e.track,
			QualifyWeatherSlot1: e.qualifyWeather1,
			QualifyWeatherSlot2: e.qualifyWeather2,
			RaceWeatherSlot1: e.raceWeather1,
			RaceWeatherSlot2: e.raceWeather2,
		})),
	});
	/* eslint-enable camelcase */

	const loadConfig = (config: any, fallbackName = '') => {
		const defaults = config?.defaultConfig ?? config?.default;
		const rotation = config?.rotation;
		if (!defaults || !Array.isArray(rotation) || rotation.length === 0 || rotation.length > MAX_ENTRIES) {
			setMessage({text: 'That is not a rotation Race Manager can read.', severity: 'error'});
			return;
		}

		const bad = rotation.some((r: any) => typeof r?.TrackId !== 'string' || !SAFE_NAME.test(r.TrackId));
		if (bad) {
			setMessage({text: 'Some rounds in that rotation have no valid track.', severity: 'error'});
			return;
		}

		setBase(defaults);
		setVehicleClass(String(defaults.VehicleClassId ?? ''));
		setRaceLaps(String(defaults.RaceLength ?? 5));
		setQualifyMinutes(String(defaults.QualifyLength ?? 5));
		setPracticeMinutes(String(defaults.PracticeLength ?? 0));
		setEntries(rotation.map((r: any) => ({
			track: r.TrackId,
			qualifyWeather1: r.QualifyWeatherSlot1 ?? defaults.QualifyWeatherSlot1 ?? CLEAR,
			qualifyWeather2: r.QualifyWeatherSlot2 ?? defaults.QualifyWeatherSlot2 ?? CLEAR,
			raceWeather1: r.RaceWeatherSlot1 ?? defaults.RaceWeatherSlot1 ?? CLEAR,
			raceWeather2: r.RaceWeatherSlot2 ?? defaults.RaceWeatherSlot2 ?? CLEAR,
		})));
		if (fallbackName) {
			setName(fallbackName);
		}

		setMessage({text: `Loaded ${rotation.length} rounds. Change anything, then save it under a new name.`, severity: 'info'});
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
			const parsed = JSON.parse(await file.text());
			loadConfig(parsed?.config ?? parsed, file.name.replace(/\.json$/i, '').replace(/[^A-Za-z0-9 _.()-]/g, '').slice(0, 64));
		} catch {
			setMessage({text: 'That file is not valid JSON.', severity: 'error'});
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
		link.download = `${name.trim() || 'rotation'}.sms_rotate_default_config.json`;
		document.body.appendChild(link);
		link.click();
		link.remove();
		URL.revokeObjectURL(url);
	};

	const save = async () => {
		setBusy(true);
		const result = await saveCustomRotation(name.trim(), buildConfig());
		setBusy(false);
		setMessage({
			text: result.ok ? 'Saved. Select it under Rotation and use Update Server to make it the active one.' : result.message,
			severity: result.ok ? 'success' : 'error',
		});
		if (result.ok) {
			fetchSavedRotations().then(setSaved);
		}
	};

	const update = (index: number, patch: Partial<IEntry>) => setEntries(entries.map((e, i) => (i === index ? {...e, ...patch} : e)));
	const move = (index: number, by: number) => {
		const target = index + by;
		if (target < 0 || target >= entries.length) {
			return;
		}

		const copy = [...entries];
		[copy[index], copy[target]] = [copy[target], copy[index]];
		setEntries(copy);
	};

	const weatherSelect = (label: string, value: string, onChange: (v: string) => void) => (
		<FormControl variant="standard" sx={{minWidth: 110}}>
			<InputLabel>{label}</InputLabel>
			<Select value={weatherOptions.includes(value) ? value : ''} onChange={e => onChange(e.target.value)}>
				{weatherOptions.map(w => <MenuItem key={w} value={w}>{pretty(w)}</MenuItem>)}
			</Select>
		</FormControl>
	);

	return (
		<Stack spacing={2} sx={{textAlign: 'left'}}>
			<Typography variant="body2">
				Build a rotation round by round: one vehicle class, then the track and weather for each round, in order. Saving keeps it with your
				other rotations; it only goes live when you select it and use Update Server.
			</Typography>

			<Stack direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{alignItems: {sm: 'flex-end'}}}>
				<FormControl variant="standard" sx={{minWidth: 240}}>
					<InputLabel id="rb-load">Start from a saved rotation</InputLabel>
					<Select labelId="rb-load" value="" onChange={e => loadSaved(e.target.value)}>
						{saved.map(s => <MenuItem key={s.id} value={s.id}>{s.name}</MenuItem>)}
					</Select>
				</FormControl>
				<Button onClick={() => fileInput.current?.click()}>Import file</Button>
				<input ref={fileInput} type="file" accept=".json,application/json" hidden onChange={e => {
					importFile(e.target.files?.[0]);
					e.target.value = '';
				}} />
			</Stack>

			<Stack direction={{xs: 'column', sm: 'row'}} spacing={2}>
				<TextField label="Rotation name" variant="standard" value={name} onChange={e => setName(e.target.value)} slotProps={{htmlInput: {maxLength: 64}}} />
				<FormControl variant="standard" sx={{minWidth: 220}}>
					<InputLabel id="rb-class">Vehicle class</InputLabel>
					<Select labelId="rb-class" value={classes.some(c => c.name === vehicleClass) ? vehicleClass : ''} onChange={e => setVehicleClass(e.target.value)}>
						{classes.map(c => <MenuItem key={c.name} value={c.name}>{pretty(c.name)}</MenuItem>)}
					</Select>
				</FormControl>
				<TextField label="Race laps" variant="standard" value={raceLaps} onChange={e => setRaceLaps(e.target.value)} sx={{width: 100}} />
				<TextField label="Qualifying min" variant="standard" value={qualifyMinutes} onChange={e => setQualifyMinutes(e.target.value)} sx={{width: 120}} />
				<TextField label="Practice min" variant="standard" value={practiceMinutes} onChange={e => setPracticeMinutes(e.target.value)} sx={{width: 110}} />
			</Stack>

			{entries.map((entry, index) => (
				<Stack key={index} direction="row" useFlexGap sx={{flexWrap: 'wrap', gap: 1.5, alignItems: 'flex-end'}} aria-label={`Round ${index + 1}`}>
					<Typography sx={{width: 28}}>{index + 1}</Typography>
					<FormControl variant="standard" sx={{minWidth: 230}}>
						<InputLabel>Track</InputLabel>
						<Select value={tracks.some(t => t.name === entry.track) ? entry.track : ''} onChange={e => update(index, {track: e.target.value})}>
							{tracks.map(t => <MenuItem key={t.name} value={t.name}>{pretty(t.name)}</MenuItem>)}
						</Select>
					</FormControl>
					{weatherSelect('Qualifying 1', entry.qualifyWeather1, v => update(index, {qualifyWeather1: v}))}
					{weatherSelect('Qualifying 2', entry.qualifyWeather2, v => update(index, {qualifyWeather2: v}))}
					{weatherSelect('Race 1', entry.raceWeather1, v => update(index, {raceWeather1: v}))}
					{weatherSelect('Race 2', entry.raceWeather2, v => update(index, {raceWeather2: v}))}
					<Tooltip title="Move up"><span><IconButton size="small" aria-label="Move up" disabled={index === 0} onClick={() => move(index, -1)}><ArrowUpwardIcon fontSize="small" /></IconButton></span></Tooltip>
					<Tooltip title="Move down"><span><IconButton size="small" aria-label="Move down" disabled={index === entries.length - 1} onClick={() => move(index, 1)}><ArrowDownwardIcon fontSize="small" /></IconButton></span></Tooltip>
					<Tooltip title="Remove round"><span><IconButton size="small" aria-label="Remove round" disabled={entries.length === 1} onClick={() => setEntries(entries.filter((_e, i) => i !== index))}><DeleteIcon fontSize="small" /></IconButton></span></Tooltip>
				</Stack>
			))}

			<Stack direction="row" spacing={2}>
				<Button disabled={entries.length >= MAX_ENTRIES} onClick={() => setEntries([...entries, {...(entries.at(-1) ?? emptyEntry()), track: ''}])}>Add round</Button>
				<Button disabled={problems.length > 0} onClick={exportFile}>Export file</Button>
				<Button variant="contained" disabled={problems.length > 0 || busy} onClick={save}>Save rotation</Button>
			</Stack>
			{problems.length > 0 && <Alert severity="info">{problems.join('. ')}</Alert>}
			{message && <Alert severity={message.severity}>{message.text}</Alert>}
		</Stack>
	);
};

export default RotationBuilder;
