import React, {useState, useEffect} from 'react';
import {useSelector, useDispatch} from 'react-redux';
import {styled} from '@mui/material/styles';

import {getSessions} from '../../state/sessions';
import {fetchSessionDetail, updateSession} from './utilities';
import {IScoring} from '../../utils/pointsCalculator';
import {SCORING_PRESETS, parsePositions, parseTeams, formatTeams, parseAdjustments, formatAdjustments} from '../../utils/scoringPresets';

import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import FormControl from '@mui/material/FormControl';
import InputLabel from '@mui/material/InputLabel';
import MenuItem from '@mui/material/MenuItem';
import Select, {SelectChangeEvent} from '@mui/material/Select';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';

const ErrorTypography = styled(Typography)(({theme}) => ({
	color: theme.palette.error.main,
}));

const ordinal = (n: number) => {
	const suffix = ['th', 'st', 'nd', 'rd'];
	const v = n % 100;
	return n + (suffix[(v - 20) % 10] || suffix[v] || suffix[0]);
};

const toInt = (value: string) => (/^\d{1,4}$/.test(value) ? Number(value) : NaN);

interface IProps {
	open: boolean,
	setOpen: (isOpen: boolean) => void
}

// Choose how a session (a championship) awards points: the built-in formula, a preset, or a custom table.
const ScoringDialog = (props: IProps) => {
	const {sessions} = useSelector((state: any) => state);
	const dispatch = useDispatch();

	const session = sessions.sessions?.find((s: any) => s.id === sessions.selectedSessionId);

	const [presetId, setPresetId] = useState('default');
	const [positionsText, setPositionsText] = useState('');
	const [poleBonus, setPoleBonus] = useState('0');
	const [fastestLapBonus, setFastestLapBonus] = useState('0');
	const [dropRounds, setDropRounds] = useState('0');
	const [teamsText, setTeamsText] = useState('');
	const [adjustmentsText, setAdjustmentsText] = useState('');
	const [saving, setSaving] = useState(false);
	const [errorMessage, setErrorMessage] = useState('');

	// Load the session's current scoring whenever the dialog opens
	useEffect(() => {
		if (!props.open) {
			return;
		}

		setErrorMessage('');
		const current: IScoring | null | undefined = session?.scoring;
		setDropRounds(String(current?.dropRounds ?? 0));
		setTeamsText(formatTeams(current?.teams));
		setAdjustmentsText(formatAdjustments(current?.adjustments));
		if (!current || current.type === 'default') {
			setPresetId('default');
			setPositionsText('');
			setPoleBonus('0');
			setFastestLapBonus('0');
			return;
		}

		const match = SCORING_PRESETS.find(p => p.scoring.type === 'table'
			&& p.scoring.positions.join(',') === current.positions.join(',')
			&& p.scoring.poleBonus === current.poleBonus && p.scoring.fastestLapBonus === current.fastestLapBonus);
		setPresetId(match ? match.id : 'custom');
		setPositionsText(current.positions.join(', '));
		setPoleBonus(String(current.poleBonus));
		setFastestLapBonus(String(current.fastestLapBonus));
	}, [props.open, session?.id]);

	const isDefault = presetId === 'default';
	const positions = parsePositions(positionsText);
	const pole = toInt(poleBonus);
	const fastest = toInt(fastestLapBonus);
	const drops = toInt(dropRounds);
	const teams = parseTeams(teamsText);
	const adjustments = parseAdjustments(adjustmentsText);
	const rulesValid = !isNaN(drops) && drops >= 0 && drops <= 50 && teams !== null && adjustments !== null;
	const valid = rulesValid && (isDefault || (positions !== null && !isNaN(pole) && !isNaN(fastest)));

	const handlePresetChange = (event: SelectChangeEvent) => {
		const id = event.target.value;
		setPresetId(id);
		const preset = SCORING_PRESETS.find(p => p.id === id);
		if (preset && preset.scoring.type === 'table') {
			setPositionsText(preset.scoring.positions.join(', '));
			setPoleBonus(String(preset.scoring.poleBonus));
			setFastestLapBonus(String(preset.scoring.fastestLapBonus));
		}
	};

	const handleSave = async () => {
		if (!session || !valid) {
			return;
		}

		setSaving(true);
		setErrorMessage('');
		const rules = {dropRounds: drops, teams: teams ?? [], adjustments: adjustments ?? []};
		const scoring: IScoring = isDefault
			? {type: 'default', positions: [], poleBonus: 0, fastestLapBonus: 0, ...rules}
			: {type: 'table', positions: positions as Array<number>, poleBonus: pole, fastestLapBonus: fastest, ...rules};

		try {
			// Send the session's current name and rotations along with the scoring so nothing else changes
			const detail = await fetchSessionDetail(session.id);
			if (!detail?.name) {
				throw new Error('Session not found');
			}

			const result = await updateSession(detail.name, session.id, detail.raceRotationIds ?? [], scoring);
			if (result) {
				dispatch(getSessions());
				props.setOpen(false);
			} else {
				setErrorMessage('Could not save the scoring. Please try again.');
			}
		} catch {
			setErrorMessage('Could not save the scoring. Please try again.');
		} finally {
			setSaving(false);
		}
	};

	const preview = positions
		? positions.slice(0, 10).map((p, i) => `${ordinal(i + 1)}: ${p}`).join('  ·  ') + (positions.length > 10 ? '  ...' : '')
		: '';

	return (
		<Dialog open={props.open} onClose={() => props.setOpen(false)} fullWidth maxWidth="sm">
			<DialogTitle>Scoring for {session?.name ?? 'session'}</DialogTitle>
			<DialogContent>
				<Stack spacing={3} sx={{pt: 1}}>
					<Typography variant="body2" align="left">
						How this session awards championship points. Changing it re-scores every race in the session,
						including past ones.
					</Typography>

					<FormControl variant="standard" fullWidth>
						<InputLabel id="scoring-preset">Scoring</InputLabel>
						<Select labelId="scoring-preset" value={presetId} onChange={handlePresetChange} label="Scoring">
							{SCORING_PRESETS.map(p => <MenuItem key={p.id} value={p.id}>{p.label}</MenuItem>)}
							<MenuItem value="custom">Custom table</MenuItem>
						</Select>
					</FormControl>

					{!isDefault && <>
						<TextField
							label="Points by finishing position"
							helperText={positions === null && positionsText !== ''
								? 'Whole numbers from 0 to 1000, separated by commas'
								: (preview || '1st, 2nd, 3rd... separated by commas. Positions beyond the list score nothing.')}
							error={positions === null && positionsText !== ''}
							variant="standard"
							fullWidth
							value={positionsText}
							onChange={e => {
								setPositionsText(e.target.value);
								setPresetId('custom');
							}}
						/>
						<Stack direction="row" spacing={3}>
							<TextField label="Pole position bonus" variant="standard" value={poleBonus}
								error={isNaN(pole)} onChange={e => setPoleBonus(e.target.value)}
								slotProps={{htmlInput: {inputMode: 'numeric', maxLength: 4}}} />
							<TextField label="Fastest lap bonus" variant="standard" value={fastestLapBonus}
								error={isNaN(fastest)} onChange={e => setFastestLapBonus(e.target.value)}
								slotProps={{htmlInput: {inputMode: 'numeric', maxLength: 4}}} />
						</Stack>
					</>}

					<Typography variant="subtitle2" align="left">Championship rules</Typography>
					<TextField
						label="Dropped rounds"
						helperText="The lowest N scores of each driver don't count. A round a driver missed counts as 0 and is dropped first."
						variant="standard"
						value={dropRounds}
						error={isNaN(drops) || drops < 0 || drops > 50}
						onChange={e => setDropRounds(e.target.value)}
						slotProps={{htmlInput: {inputMode: 'numeric', maxLength: 2}}}
					/>
					<TextField
						label="Teams (optional)"
						helperText="One per line: Team name: driver, driver. A team's points are its drivers' totals added up."
						variant="standard"
						multiline
						minRows={2}
						error={teams === null}
						value={teamsText}
						onChange={e => setTeamsText(e.target.value)}
					/>
					<TextField
						label="Point adjustments (optional)"
						helperText="One per line: driver, points, note. Use a minus for a penalty, e.g. Ayrton S, -5, track limits."
						variant="standard"
						multiline
						minRows={2}
						error={adjustments === null}
						value={adjustmentsText}
						onChange={e => setAdjustmentsText(e.target.value)}
					/>

					<ErrorTypography>{errorMessage}</ErrorTypography>
				</Stack>
			</DialogContent>
			<DialogActions>
				<Button onClick={() => props.setOpen(false)} disabled={saving}>Cancel</Button>
				<Button onClick={handleSave} disabled={!valid || !session} loading={saving}>Save scoring</Button>
			</DialogActions>
		</Dialog>
	);
};

export default ScoringDialog;
