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
