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
