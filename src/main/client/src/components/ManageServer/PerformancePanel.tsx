import React, {useEffect, useState} from 'react';

import {fetchPerformance, savePerformance, IPerformance, IPerformanceTable} from '../Live/adminApi';
import {fetchLiveSession} from '../Live/liveApi';

import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import DeleteIcon from '@mui/icons-material/Delete';

type Kind = 'ballast' | 'power' | 'drag';

const KINDS: Array<{kind: Kind, label: string, unit: string, min: number, max: number}> = [
	{kind: 'ballast', label: 'Ballast', unit: 'kg', min: 0, max: 500},
	{kind: 'power', label: 'Engine power', unit: '× power', min: 0.5, max: 1.5},
	{kind: 'drag', label: 'Drag', unit: '× drag', min: 0.5, max: 1.5},
];

// One text value per cell, kept as text while typing; an empty cell means "use the default"
interface IRow {
	steamId: string,
	values: Record<Kind, string>,
}

const num = (text: string) => (/^\d{1,4}(\.\d{1,4})?$/.test(text.trim()) ? Number(text) : NaN);

// Per-driver ballast, engine power and drag in one table, with the three tables' defaults and switches above it.
const PerformancePanel = () => {
	const [loaded, setLoaded] = useState<IPerformance | null>(null);
	const [problem, setProblem] = useState('');
	const [enabled, setEnabled] = useState<Record<Kind, boolean>>({ballast: false, power: false, drag: false});
	const [defaults, setDefaults] = useState<Record<Kind, string>>({ballast: '0', power: '1', drag: '1'});
	const [rows, setRows] = useState<Array<IRow>>([]);
	const [names, setNames] = useState<Record<string, string>>({});
	const [feedback, setFeedback] = useState<{text: string, severity: 'success' | 'error'} | null>(null);
	const [busy, setBusy] = useState(false);

	const load = async () => {
		const result = await fetchPerformance();
		if (!result.ok || !result.data) {
			setProblem(result.message || 'Could not read the performance files');
			return;
		}

		const {data} = result;
		setLoaded(data);
		setProblem('');
		setEnabled({ballast: data.ballast.enabled, power: data.power.enabled, drag: data.drag.enabled});
		setDefaults({ballast: String(data.ballast.defaultValue), power: String(data.power.defaultValue), drag: String(data.drag.defaultValue)});
		const ids = new Set([...Object.keys(data.ballast.drivers), ...Object.keys(data.power.drivers), ...Object.keys(data.drag.drivers)]);
		setRows(Array.from(ids).map(steamId => ({
			steamId,
			values: {
				ballast: data.ballast.drivers[steamId] === undefined ? '' : String(data.ballast.drivers[steamId]),
				power: data.power.drivers[steamId] === undefined ? '' : String(data.power.drivers[steamId]),
				drag: data.drag.drivers[steamId] === undefined ? '' : String(data.drag.drivers[steamId]),
			},
		})));
	};

	useEffect(() => {
		load();
		// Names of the people connected right now, to recognise their Steam IDs
		fetchLiveSession(true).then(live => setNames(Object.fromEntries((live?.members ?? []).filter(m => m.steamid).map(m => [m.steamid as string, m.name]))));
	}, []);

	const errors: Array<string> = [];
	KINDS.forEach(k => {
		const d = num(defaults[k.kind]);
		if (isNaN(d) || d < k.min || d > k.max) {
			errors.push(`Default ${k.label.toLowerCase()} must be ${k.min} to ${k.max}`);
		}
	});
	rows.forEach(r => {
		if (!/^\d{17}$/.test(r.steamId)) {
			errors.push(`"${r.steamId}" is not a 17 digit Steam ID`);
		}

		KINDS.forEach(k => {
			const text = r.values[k.kind];
			const v = num(text);
			if (text.trim() !== '' && (isNaN(v) || v < k.min || v > k.max)) {
				errors.push(`${r.steamId}: ${k.label.toLowerCase()} must be ${k.min} to ${k.max}`);
			}
		});
	});

	const build = (): IPerformance => {
		const table = (kind: Kind): IPerformanceTable => ({
			enabled: enabled[kind],
			defaultValue: num(defaults[kind]),
			drivers: Object.fromEntries(rows.filter(r => r.values[kind].trim() !== '').map(r => [r.steamId, num(r.values[kind])])),
		});
		return {ballast: table('ballast'), power: table('power'), drag: table('drag')};
	};

	const save = async () => {
		setBusy(true);
		const result = await savePerformance(build());
		setBusy(false);
		setFeedback({text: result.message, severity: result.ok ? 'success' : 'error'});
		if (result.ok) {
			load();
		}
	};

	const updateRow = (index: number, patch: Partial<IRow>) => setRows(rows.map((r, i) => (i === index ? {...r, ...patch} : r)));

	if (problem) {
		return <Alert severity="warning">{problem}</Alert>;
	}

	if (!loaded) {
		return <Typography variant="body2" color="text.secondary">Loading...</Typography>;
	}

	return (
		<Stack spacing={2} sx={{textAlign: 'left'}}>
			<Typography variant="body2">
				Slow down fast drivers or help slower ones. A driver with an empty cell gets the default; drivers not listed get the default too.
				The game server reads these files when it loads its configuration, so <strong>restart it</strong> to apply changes.
			</Typography>

			<Stack direction="row" useFlexGap sx={{flexWrap: 'wrap', gap: 3}}>
				{KINDS.map(k => (
					<Stack key={k.kind} direction="row" spacing={1} sx={{alignItems: 'flex-end'}}>
						<FormControlLabel label={`${k.label} on`} control={
							<Checkbox checked={enabled[k.kind]} onChange={e => setEnabled({...enabled, [k.kind]: e.target.checked})} />} />
						<TextField label={`Default (${k.unit})`} variant="standard" sx={{width: 130}} value={defaults[k.kind]}
							onChange={e => setDefaults({...defaults, [k.kind]: e.target.value})} />
					</Stack>
				))}
			</Stack>

			<Table size="small" aria-label="Balance of performance">
				<TableHead>
					<TableRow>
						<TableCell>Steam ID</TableCell>
						{KINDS.map(k => <TableCell key={k.kind}>{k.label} ({k.unit})</TableCell>)}
						<TableCell />
					</TableRow>
				</TableHead>
				<TableBody>
					{rows.map((r, index) => (
						<TableRow key={index}>
							<TableCell>
								<TextField variant="standard" value={r.steamId} sx={{width: 190}} onChange={e => updateRow(index, {steamId: e.target.value.trim()})}
									error={!/^\d{17}$/.test(r.steamId)} helperText={names[r.steamId] ?? ''} slotProps={{htmlInput: {maxLength: 17, 'aria-label': 'Steam ID'}}} />
							</TableCell>
							{KINDS.map(k => (
								<TableCell key={k.kind}>
									<TextField variant="standard" sx={{width: 90}} value={r.values[k.kind]} placeholder={defaults[k.kind]}
										onChange={e => updateRow(index, {values: {...r.values, [k.kind]: e.target.value}})}
										slotProps={{htmlInput: {'aria-label': `${k.label} for ${r.steamId}`}}} />
								</TableCell>
							))}
							<TableCell align="right">
								<IconButton size="small" aria-label="Remove driver" onClick={() => setRows(rows.filter((_r, i) => i !== index))}><DeleteIcon fontSize="small" /></IconButton>
							</TableCell>
						</TableRow>
					))}
				</TableBody>
			</Table>
			{rows.length === 0 && <Typography variant="body2" color="text.secondary">No drivers listed.</Typography>}

			<Stack direction="row" spacing={2}>
				<Button onClick={() => setRows([...rows, {steamId: '', values: {ballast: '', power: '', drag: ''}}])}>Add driver</Button>
				<Button variant="contained" disabled={errors.length > 0 || busy} onClick={save}>Save</Button>
			</Stack>
			{errors.length > 0 && <Alert severity="info">{[...new Set(errors)].slice(0, 3).join('. ')}</Alert>}
			{feedback && <Alert severity={feedback.severity}>{feedback.text}</Alert>}
		</Stack>
	);
};

export default PerformancePanel;
