import {IScoring} from './pointsCalculator';

export interface IScoringPreset {
	id: string,
	label: string,
	scoring: IScoring,
}

export const SCORING_PRESETS: Array<IScoringPreset> = [
	{id: 'default', label: 'Built-in formula (half the field scores)', scoring: {type: 'default', positions: [], poleBonus: 0, fastestLapBonus: 0}},
	{id: 'f1', label: 'F1 style (25-18-15-12-10-8-6-4-2-1)', scoring: {type: 'table', positions: [25, 18, 15, 12, 10, 8, 6, 4, 2, 1], poleBonus: 0, fastestLapBonus: 1}},
	{id: 'f1-classic', label: 'F1 2003-2009 (10-8-6-5-4-3-2-1)', scoring: {type: 'table', positions: [10, 8, 6, 5, 4, 3, 2, 1], poleBonus: 0, fastestLapBonus: 0}},
	{id: 'top10', label: 'Top 10 (10 down to 1)', scoring: {type: 'table', positions: [10, 9, 8, 7, 6, 5, 4, 3, 2, 1], poleBonus: 1, fastestLapBonus: 1}},
];

export const MAX_POSITIONS = 100;
export const MAX_POINTS = 1000;

// Reads "25, 18, 15" (commas or spaces). Returns null when anything is not a valid whole number of points.
export const parsePositions = (text: string): Array<number> | null => {
	const parts = text.split(/[\s,]+/).filter(p => p !== '');
	if (parts.length === 0 || parts.length > MAX_POSITIONS) {
		return null;
	}

	const numbers = parts.map(p => (/^\d+$/.test(p) ? Number(p) : NaN));
	return numbers.some(n => isNaN(n) || n > MAX_POINTS) ? null : numbers;
};

export interface ITeamEntry {
	name: string,
	drivers: Array<string>,
}

export interface IAdjustmentEntry {
	driver: string,
	points: number,
	note: string,
}

// "Red Team: Ayrton S, Alain P" per line. Returns null when a line is malformed or a driver is in two teams.
export const parseTeams = (text: string): Array<ITeamEntry> | null => {
	const teams: Array<ITeamEntry> = [];
	const seen = new Set<string>();
	for (const line of text.split('\n').map(l => l.trim()).filter(l => l !== '')) {
		const colon = line.indexOf(':');
		const name = colon < 0 ? '' : line.slice(0, colon).trim();
		const drivers = colon < 0 ? [] : line.slice(colon + 1).split(',').map(d => d.trim()).filter(d => d !== '');
		if (!name || name.length > 64 || drivers.length === 0 || drivers.length > 50 || drivers.some(d => d.length > 64)) {
			return null;
		}

		for (const driver of drivers) {
			if (seen.has(driver)) {
				return null;
			}

			seen.add(driver);
		}

		teams.push({name, drivers});
	}

	return teams.length > 50 ? null : teams;
};

export const formatTeams = (teams: Array<ITeamEntry> = []) => teams.map(t => `${t.name}: ${t.drivers.join(', ')}`).join('\n');

// "Ayrton S, -5, track limits" per line (note optional). Returns null when a line is malformed.
export const parseAdjustments = (text: string): Array<IAdjustmentEntry> | null => {
	const entries: Array<IAdjustmentEntry> = [];
	for (const line of text.split('\n').map(l => l.trim()).filter(l => l !== '')) {
		const first = line.indexOf(',');
		if (first < 0) {
			return null;
		}

		const driver = line.slice(0, first).trim();
		const rest = line.slice(first + 1);
		const second = rest.indexOf(',');
		const pointsText = (second < 0 ? rest : rest.slice(0, second)).trim();
		const note = second < 0 ? '' : rest.slice(second + 1).trim();
		const points = /^[+-]?\d{1,4}$/.test(pointsText) ? Number(pointsText) : NaN;
		if (!driver || driver.length > 64 || isNaN(points) || Math.abs(points) > MAX_POINTS || note.length > 100) {
			return null;
		}

		entries.push({driver, points, note});
	}

	return entries.length > 200 ? null : entries;
};

export const formatAdjustments = (entries: Array<{driver: string, points: number, note?: string}> = []) =>
	entries.map(a => `${a.driver}, ${a.points}${a.note ? `, ${a.note}` : ''}`).join('\n');
