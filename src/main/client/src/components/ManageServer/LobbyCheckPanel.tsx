import React, {useEffect, useState} from 'react';
import {fetchLobbyCheck, ILobbyCheck} from '../Live/adminApi';
import {labelOf} from '../../utils/sessionSettings';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import FormControlLabel from '@mui/material/FormControlLabel';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';

const REFRESH_MS = 10_000;

// Flags as a list, so a difference stands out
const flagList = (text: string | null) => (text ?? '').split(',').filter(f => f && f !== '(none)');

const FlagsCell = ({value, other}: {value: string | null, other: string | null}) => {
	if (value === null) {
		return <>—</>;
	}

	const otherFlags = other === null ? null : flagList(other);
	const flags = flagList(value);
	return flags.length === 0 ? <>(none)</> : (
		<Stack direction="row" useFlexGap sx={{flexWrap: 'wrap', gap: 0.5}}>
			{flags.map(f => <Chip key={f} size="small" label={f} color={otherFlags && !otherFlags.includes(f) ? 'warning' : 'default'} />)}
		</Stack>
	);
};

// What the game server's live session really has, next to what the rotation on the game server asks for
const LobbyCheckPanel = () => {
	const [check, setCheck] = useState<ILobbyCheck | null>(null);
	const [problem, setProblem] = useState('');
	const [onlyDifferent, setOnlyDifferent] = useState(false);

	const load = async () => {
		const result = await fetchLobbyCheck();
		setCheck(result.ok ? result.data ?? null : null);
		setProblem(result.ok ? '' : result.message || 'The game server isn\'t answering.');
	};

	useEffect(() => {
		load();
		const timer = setInterval(load, REFRESH_MS);
		return () => clearInterval(timer);
	}, []);

	const rows = (check?.rows ?? []).filter(r => !onlyDifferent || r.match === false);
	const differences = (check?.rows ?? []).filter(r => r.match === false).length;

	return (
		<Stack spacing={2} sx={{textAlign: 'left'}}>
			<Typography variant="body2">
				The settings the game server&apos;s session really has, next to what its rotation file asks for (the default setup plus the round). Refreshes every 10 seconds.
			</Typography>
			{problem && <Alert severity="warning">{problem}</Alert>}
			{check && (check.sessionOpen
				? <Alert severity={differences > 0 ? 'warning' : 'success'}>
					{`Session ${check.state ?? ''} on ${check.liveTrack ?? 'an unknown track'}`}
					{check.round ? ` · round ${check.round} of ${check.rounds}` : ''}
					{differences > 0 ? ` · ${differences} setting${differences === 1 ? '' : 's'} differ` : ' · everything matches'}
				</Alert>
				: <Alert severity="info">
					No lobby open right now. When someone joins, the live values show here.
					{check.round ? ` The next lobby should get round ${check.round} of ${check.rounds}:` : ''}
				</Alert>)}
			{check?.note && <Alert severity="info">{check.note}</Alert>}
			{check && check.rows.length > 0 && <>
				{check.sessionOpen && <FormControlLabel control={<Switch checked={onlyDifferent} onChange={e => setOnlyDifferent(e.target.checked)} />}
					label="Only show differences" />}
				<Table size="small" aria-label="Lobby check">
					<TableHead>
						<TableRow>
							<TableCell>Setting</TableCell>
							<TableCell>Rotation</TableCell>
							<TableCell>Live session</TableCell>
							<TableCell />
						</TableRow>
					</TableHead>
					<TableBody>
						{rows.map(r => (
							<TableRow key={r.name} sx={r.match === false ? {backgroundColor: 'rgba(237, 108, 2, 0.12)'} : undefined}>
								<TableCell>{labelOf(r.name)}</TableCell>
								<TableCell>{r.name === 'Flags' ? <FlagsCell value={r.expected} other={r.live} /> : r.expected ?? '—'}</TableCell>
								<TableCell>{r.name === 'Flags' ? <FlagsCell value={r.live} other={r.expected} /> : r.live ?? '—'}</TableCell>
								<TableCell>{r.match === true ? '✓' : r.match === false ? 'differs' : ''}</TableCell>
							</TableRow>
						))}
					</TableBody>
				</Table>
			</>}
			<Stack direction="row"><Button onClick={load}>Refresh</Button></Stack>
		</Stack>
	);
};

export default LobbyCheckPanel;
