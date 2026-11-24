// Helpers for editing session settings the way the game server describes them (see /api/v1/admin/gameserver/schema).
export interface IOption {
	name: string,
	value: number,
}

export interface IAttribute {
	name: string,
	type: string,
	description: string,
	kind: 'enum' | 'flags' | 'track' | 'vehicleClass' | 'vehicle' | 'toggle' | 'number',
	enumName?: string,
}

export interface ISchema {
	attributes: Array<IAttribute>,
	enums: Record<string, Array<IOption>>,
	flags: Array<IOption>,
}

export const GROUPS = ['Session',
	'Cars and grid',
	'Rules and realism',
	'Start and pit stops',
	'Practice weather and time',
	'Qualifying weather and time',
	'Race weather and time',
	'Other'] as const;

const SESSION = new Set(['TrackId',
	'PracticeLength',
	'QualifyLength',
	'RaceLength',
	'RaceExtraLap',
	'QualifyStintLimit',
	'QualifyLapLimit',
	'QualifyPrivateSession',
	'AutoAdvanceSession']);
const CARS = new Set(['VehicleClassId',
	'VehicleModelId',
	'MultiClassSlots',
	'GridSize',
	'MaxPlayers',
	'GridLayout',
	'OpponentDifficulty',
	'MinimumOnlineRank',
	'MinimumOnlineStrength']);
const RULES = new Set(['Flags',
	'DamageType',
	'DamageScale',
	'DamageRandomFailures',
	'TireWearType',
	'FuelUsageType',
	'PenaltiesType',
	'AllowedCutsBeforePenalty',
	'PitWhiteLinePenalty',
	'DriveThroughPenalty',
	'AllowedViews',
	'FullCourseYellows',
	'RaceScheduledFullCourseYellow']);
const START_AND_PITS = new Set(['RaceRollingStart',
	'RaceFormationLap',
	'ManualRollingStarts',
	'RaceMandatoryPitStops',
	'RaceMandatoryPitStopsMinTyres',
	'DisablePitstopRefuelling',
	'PitSpeedLimit',
	'ManualPitStops']);

export const groupOf = (name: string): typeof GROUPS[number] => {
	if (SESSION.has(name)) {
		return 'Session';
	}

	if (CARS.has(name) || /^MultiClassSlot\d$/.test(name)) {
		return 'Cars and grid';
	}

	if (RULES.has(name)) {
		return 'Rules and realism';
	}

	if (START_AND_PITS.has(name)) {
		return 'Start and pit stops';
	}

	if (/^Practice(Date|Weather|LiveTrack)/.test(name)) {
		return 'Practice weather and time';
	}

	if (/^Qualify(Date|Weather|LiveTrack)/.test(name)) {
		return 'Qualifying weather and time';
	}

	if (/^Race(Date|Weather|LiveTrack)/.test(name)) {
		return 'Race weather and time';
	}

	return 'Other';
};

// "RaceMandatoryPitStopsMinTyres" -> "Race mandatory pit stops min tyres"
export const labelOf = (name: string): string => {
	const words = name.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/([A-Z])([A-Z][a-z])/g, '$1 $2').toLowerCase();
	return words.charAt(0).toUpperCase() + words.slice(1);
};

// "LIGHT_RAIN" / "LightRain" -> "Light rain"
export const prettyOption = (name: string): string => labelOf(name.includes('_') ? name.toLowerCase().replace(/_([a-z])/g, (_m, c: string) => c.toUpperCase()) : name);

// Rotation files accept either the option's name or its number. Names are kept when they are plain text (the
// rotation check only accepts plain text), numbers otherwise ("25%", "Default Progressing").
const PLAIN = /^[A-Za-z0-9_,.()\- ]{1,200}$/;
export const storedOption = (option: IOption): string | number => (PLAIN.test(option.name) && !option.name.includes(' ') ? option.name : option.value);

// The option a stored value stands for: by name (any case) or by number
export const findOption = (value: unknown, options: Array<IOption>): IOption | undefined => {
	if (typeof value === 'number') {
		return options.find(o => o.value === value);
	}

	if (typeof value === 'string') {
		const lower = value.toLowerCase();
		return options.find(o => o.name.toLowerCase() === lower) ?? (/^-?\d+$/.test(value) ? options.find(o => o.value === Number(value)) : undefined);
	}

	return undefined;
};

// The flags a stored value turns on: a comma-separated list of names, or the number they add up to
export const flagNames = (value: unknown, flags: Array<IOption>): Array<string> => {
	if (typeof value === 'number') {
		// The game treats the flags as an unsigned 32-bit set (a file may hold it as a negative number); each flag is one bit
		const bits = value < 0 ? value + (2 ** 32) : value;
		return flags.filter(f => f.value > 0 && Math.floor(bits / f.value) % 2 === 1).map(f => f.name);
	}

	if (typeof value === 'string') {
		const wanted = value.split(',').map(s => s.trim().toUpperCase()).filter(Boolean);
		return flags.filter(f => wanted.includes(f.name.toUpperCase())).map(f => f.name);
	}

	return [];
};

// Flags are written as names, which read better and are what the game's own samples use
export const formatFlags = (names: Array<string>, flags: Array<IOption>): string => flags.filter(f => names.includes(f.name)).map(f => f.name).join(',');

export interface INamed {
	id: string | number,
	name: string,
}

// The game server's "next session" call takes whole numbers only, while rotations also use names ("FULL",
// "Clear", "ABS_ALLOWED,SC_ALLOWED", "Interlagos_GP"). This turns a setup into numbers using the server's own
// lists. Settings it can't turn into a number are left out and listed in `skipped`.
const toInt32 = (n: number) => (n > 2147483647 ? n - (2 ** 32) : n);

export const toNextSessionValues = (settings: Record<string, unknown>, schema: ISchema,
	lists: {tracks: Array<INamed>, classes: Array<INamed>, vehicles: Array<INamed>}): {values: Record<string, number>, skipped: Array<string>} => {
	const values: Record<string, number> = {};
	const skipped: Array<string> = [];
	for (const [name, value] of Object.entries(settings)) {
		const attribute = schema.attributes.find(a => a.name === name);
		let n: number | undefined;
		if (!attribute) {
			n = undefined;
		} else if (attribute.kind === 'flags' && value !== undefined) {
			n = toInt32(flagNames(value, schema.flags).reduce((sum, f) => sum + schema.flags.find(x => x.name === f)!.value, 0));
		} else if (attribute.kind === 'enum' && attribute.enumName) {
			n = findOption(value, schema.enums[attribute.enumName] ?? [])?.value;
		} else if (attribute.kind === 'track' || attribute.kind === 'vehicleClass' || attribute.kind === 'vehicle') {
			const list = attribute.kind === 'track' ? lists.tracks : attribute.kind === 'vehicleClass' ? lists.classes : lists.vehicles;
			const item = list.find(i => i.name === value || String(i.id) === String(value));
			n = item ? Number(item.id) : (typeof value === 'number' ? value : undefined);
		} else if (typeof value === 'number') {
			n = value;
		} else if (typeof value === 'string' && /^-?\d{1,10}$/.test(value.trim())) {
			n = Number(value);
		}

		if (n === undefined || !Number.isInteger(n)) {
			skipped.push(name);
		} else {
			values[name] = n;
		}
	}

	return {values, skipped};
};
