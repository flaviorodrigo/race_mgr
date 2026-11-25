import React, {useState} from 'react';

import {
	findOption, flagNames, formatFlags, groupOf, GROUPS, IAttribute, ISchema, labelOf, prettyOption, storedOption,
} from '../../utils/sessionSettings';

import Accordion from '@mui/material/Accordion';
import AccordionDetails from '@mui/material/AccordionDetails';
import AccordionSummary from '@mui/material/AccordionSummary';
import Autocomplete from '@mui/material/Autocomplete';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Checkbox from '@mui/material/Checkbox';
import FormControlLabel from '@mui/material/FormControlLabel';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';

export interface INamedItem {
	id?: string | number,
	value?: number,
	name: string,
	translated_name?: string,
}

interface IProps {
	schema: ISchema | null,
	values: Record<string, unknown>,
	onChange: (values: Record<string, unknown>) => void,
	tracks: Array<INamedItem>,
	classes: Array<INamedItem>,
	vehicles: Array<INamedItem>,
	// Settings edited elsewhere (e.g. a round's track)
	exclude?: Array<string>,
	// Name used to keep field ids unique when several editors are on screen
	idPrefix: string,
}

const NOT_SET = '';

// The setting is left out of the file when it's not set: the game (or the default setup, for a round) decides
const without = (values: Record<string, unknown>, name: string) => {
	const copy = {...values};
	delete copy[name];
	return copy;
};

const listItem = (items: Array<INamedItem>, value: unknown) => items.find(i => i.name === value || String(i.id ?? i.value) === String(value));

interface IFieldProps {
	attribute: IAttribute,
	value: unknown,
	set: (v: unknown) => void,
	schema: ISchema | null,
	tracks: Array<INamedItem>,
	classes: Array<INamedItem>,
	vehicles: Array<INamedItem>,
	idPrefix: string,
}

const cleanHelp = (desc: string): string => desc
	.replace(/\s*(The|he)?\s*enum values are available[\s\S]*$/i, '')
	.replace(/\s*The flag values are available[\s\S]*$/i, '')
	.trim();

const Choice = ({id, label, help, notSet, options, current, pick}: {id: string, label: string, help: string, notSet: boolean,
	options: Array<{key: string, label: string}>, current: string, pick: (key: string) => void}) => {
	const text = cleanHelp(help);
	if (options.length > 12) {
		const selected = options.find(o => o.key === current) ?? null;
		return (
			<Autocomplete
				id={id}
				size="small"
				options={options}
				value={selected}
				getOptionLabel={o => o.label}
				isOptionEqualToValue={(a, b) => a.key === b.key}
				onChange={(_e, next) => pick(next ? next.key : NOT_SET)}
				renderInput={params => (
					<TextField {...params} variant="standard" label={label} title={help}
						placeholder="Type to filter..." helperText={text || (notSet ? 'Not set' : undefined)} />
				)}
			/>
		);
	}

	return (
		<TextField select fullWidth variant="standard" id={id} label={label} title={help} value={current}
			onChange={e => pick(e.target.value)} helperText={text || (notSet ? 'Not set' : undefined)}>
			<MenuItem value={NOT_SET}><em>Not set</em></MenuItem>
			{options.map(o => <MenuItem key={o.key} value={o.key}>{o.label}</MenuItem>)}
		</TextField>
	);
};

// For settings picked from a list (an option list, tracks, car classes, cars): the choices, the current one, and
// what to store for a choice. A value the list doesn't know is kept and offered as it is.
const pickerFor = (attribute: IAttribute, value: unknown, schema: ISchema | null,
	lists: {tracks: Array<INamedItem>, classes: Array<INamedItem>, vehicles: Array<INamedItem>}) => {
	const raw = (known: boolean) => (value !== undefined && !known ? [{key: `raw:${String(value)}`, label: `${String(value)} (as in the file)`}] : []);
	const options = attribute.kind === 'enum' && attribute.enumName ? schema?.enums[attribute.enumName] : undefined;
	if (options) {
		const current = findOption(value, options);
		const unknown = raw(Boolean(current));
		return {
			options: [...unknown, ...options.map(o => ({key: o.name, label: prettyOption(o.name)}))],
			current: current ? current.name : (unknown[0]?.key ?? NOT_SET),
			toStored: (key: string) => storedOption(options.find(o => o.name === key)!),
		};
	}

	const items = {track: lists.tracks, vehicleClass: lists.classes, vehicle: lists.vehicles}[attribute.kind as 'track' | 'vehicleClass' | 'vehicle'];
	if (items) {
		const current = listItem(items, value);
		const unknown = raw(Boolean(current));
		return {
			options: [...unknown, ...items
				.map(i => ({key: i.name, label: i.translated_name ? `${i.translated_name} · ${i.name}` : i.name.replaceAll('_', ' ')}))
				.sort((a, b) => a.label.localeCompare(b.label))],
			current: current ? current.name : (unknown[0]?.key ?? NOT_SET),
			toStored: (key: string) => key,
		};
	}

	return null;
};

// One field, with the control that fits what the game server says about it
const Field = ({attribute, value, set, schema, tracks, classes, vehicles, idPrefix}: IFieldProps) => {
	const id = `${idPrefix}-${attribute.name}`;
	const label = labelOf(attribute.name);
	const help = attribute.description;

	if (attribute.kind === 'flags' && schema) {
		const defaultFlags = ['ALLOW_CUSTOM_VEHICLE_SETUP', 'ABS_ALLOWED', 'SC_ALLOWED', 'TCS_ALLOWED', 'AUTO_START_ENGINE', 'PIT_SPEED_LIMITER', 'ANTI_GRIEFING_COLLISIONS', 'PASSWORD_PROTECTED'];
		const on = value === undefined ? [] : flagNames(value, schema.flags);
		return (
			<Box sx={{gridColumn: '1 / -1'}}>
				<Typography variant="subtitle2" title={help}>{label}{value === undefined ? ' (not set: the game decides)' : ''}</Typography>
				<Typography variant="caption" color="text.secondary" display="block" sx={{mb: 0.5}}>
					{cleanHelp(help) || 'Session rule flags.'} Note: &quot;Fill session with ai&quot; requires Grid Size &gt; Max Players.
				</Typography>
				<Box sx={{display: 'grid', gridTemplateColumns: {xs: '1fr', sm: '1fr 1fr', md: '1fr 1fr 1fr'}}}>
					{schema.flags.map(f => (
						<FormControlLabel key={f.name} label={prettyOption(f.name)}
							control={<Checkbox size="small" checked={on.includes(f.name)}
								onChange={e => {
									const base = value === undefined ? schema.flags.filter(x => defaultFlags.includes(x.name)).map(x => x.name) : on;
									const next = e.target.checked ? Array.from(new Set([...base, f.name])) : base.filter(n => n !== f.name);
									set(formatFlags(next, schema.flags));
								}} />} />
					))}
				</Box>
				{value !== undefined && <Button size="small" onClick={() => set(undefined)}>Clear (let the game decide)</Button>}
			</Box>
		);
	}

	const select = (options: Array<{key: string, label: string}>, current: string, pick: (key: string) => void) =>
		<Choice id={id} label={label} help={help} notSet={value === undefined} options={options} current={current} pick={pick} />;

	const picker = pickerFor(attribute, value, schema, {tracks, classes, vehicles});
	if (picker) {
		return select(picker.options, picker.current, key => {
			if (key === NOT_SET) {
				set(undefined);
			} else if (!key.startsWith('raw:')) {
				set(picker.toStored(key));
			}
		});
	}

	if (attribute.kind === 'toggle') {
		const current = value === undefined ? NOT_SET : Number(value) === 0 ? '0' : '1';
		return select([{key: '1', label: 'On'}, {key: '0', label: 'Off'}], current, key => set(key === NOT_SET ? undefined : Number(key)));
	}

	return <NumberField id={id} label={label} help={help} value={value} set={set} />;
};

// Whole numbers; empty means not set. Text that isn't a number yet is kept while typing and not saved.
const NumberField = ({id, label, help, value, set}: {id: string, label: string, help: string, value: unknown, set: (v: unknown) => void}) => {
	const [draft, setDraft] = useState<string | null>(null);
	const shown = draft ?? (value === undefined ? '' : String(value));
	const valid = shown === '' || /^-?\d{1,10}$/.test(shown.trim());
	const text = cleanHelp(help);
	return (
		<TextField fullWidth variant="standard" id={id} label={label} title={help} value={shown} error={!valid}
			helperText={valid ? (text || (value === undefined ? 'Not set' : undefined)) : 'A whole number'}
			onChange={e => {
				const text = e.target.value;
				if (text.trim() === '') {
					setDraft(null);
					set(undefined);
				} else if (/^-?\d{1,10}$/.test(text.trim())) {
					setDraft(null);
					set(Number(text.trim()));
				} else {
					setDraft(text);
				}
			}} />
	);
};

// Every session setting the game server offers, grouped. Without the server's description (it isn't reachable),
// the settings already in the file are still shown and editable as plain values.
const SettingsEditor = (props: IProps) => {
	const {schema, values, onChange, exclude = []} = props;
	const set = (name: string) => (v: unknown) => onChange(v === undefined ? without(values, name) : {...values, [name]: v});

	const attributes: Array<IAttribute> = schema
		? schema.attributes
		: Object.keys(values).map(name => ({name, type: '', description: '', kind: 'number' as const}));
	const shown = attributes.filter(a => !exclude.includes(a.name));

	return (
		<Box>
			{!schema && <Typography variant="body2" color="text.secondary" sx={{mb: 1}}>
				The game server isn&apos;t answering, so only the settings already in this rotation are shown, as plain values.
			</Typography>}
			{GROUPS.map(group => {
				const fields = shown.filter(a => groupOf(a.name) === group);
				if (fields.length === 0) {
					return null;
				}

				const inUse = fields.filter(a => values[a.name] !== undefined).length;
				return (
					<Accordion key={group} disableGutters slotProps={{transition: {unmountOnExit: true}}}>
						<AccordionSummary expandIcon={<ExpandMoreIcon />}>
							<Typography>{group}{inUse > 0 ? ` (${inUse} set)` : ''}</Typography>
						</AccordionSummary>
						<AccordionDetails>
							<Box sx={{display: 'grid', gap: 2, gridTemplateColumns: {xs: '1fr', sm: '1fr 1fr', md: '1fr 1fr 1fr'}}}>
								{fields.map(a => <Field key={a.name} attribute={a} value={values[a.name]} set={set(a.name)} schema={schema}
									tracks={props.tracks} classes={props.classes} vehicles={props.vehicles} idPrefix={props.idPrefix} />)}
							</Box>
						</AccordionDetails>
					</Accordion>
				);
			})}
		</Box>
	);
};

export default SettingsEditor;
