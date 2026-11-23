import {describe, expect, it} from 'vitest';
import {calculatePoints} from './pointsCalculator';
import {sanitizeString} from './stringUtils';
import {parsePositions} from './scoringPresets';

describe('calculatePoints', () => {
	it('gives the winner a 40% bonus over a half-field points scale', () => {
		// 10 players: points for the first 5 places; winner = (5 - 1) * 2 = 8, +40% = 11.2
		expect(calculatePoints(1, 10)).toBe(11);
	});

	it('gives nothing outside the points positions', () => {
		expect(calculatePoints(9, 10)).toBe(0);
	});

	it('adds pole and fastest lap bonuses', () => {
		expect(calculatePoints(9, 10, {tookPole: true, fastestLap: true})).toBe(2);
	});
});

describe('sanitizeString', () => {
	it('strips everything but letters, digits and spaces', () => {
		expect(sanitizeString('Race #1: <b>Spa</b>!')).toBe('Race 1 bSpab');
	});
});

describe('scoring tables', () => {
	const f1 = {type: 'table' as const, positions: [25, 18, 15], poleBonus: 1, fastestLapBonus: 2};

	it('awards points by finishing position', () => {
		expect(calculatePoints(1, 20, {tookPole: false, fastestLap: false, scoring: f1})).toBe(25);
		expect(calculatePoints(3, 20, {tookPole: false, fastestLap: false, scoring: f1})).toBe(15);
	});

	it('scores nothing beyond the table', () => {
		expect(calculatePoints(4, 20, {tookPole: false, fastestLap: false, scoring: f1})).toBe(0);
	});

	it('adds pole and fastest lap bonuses, even outside the points', () => {
		expect(calculatePoints(2, 20, {tookPole: true, fastestLap: true, scoring: f1})).toBe(18 + 1 + 2);
		expect(calculatePoints(9, 20, {tookPole: true, fastestLap: false, scoring: f1})).toBe(1);
	});

	it('falls back to the built-in formula', () => {
		expect(calculatePoints(1, 10, {tookPole: false, fastestLap: false, scoring: null})).toBe(11);
		expect(calculatePoints(1, 10, {tookPole: false, fastestLap: false, scoring: {type: 'default', positions: [], poleBonus: 0, fastestLapBonus: 0}})).toBe(11);
	});
});

describe('parsePositions', () => {
	it('accepts comma and space separated points', () => {
		expect(parsePositions('25, 18 15,12')).toEqual([25, 18, 15, 12]);
	});

	it('rejects junk, negatives, decimals, empties and huge values', () => {
		for (const bad of ['', 'a,b', '-1', '2.5', '5000', '10,,x']) {
			expect(parsePositions(bad)).toBeNull();
		}
	});
});
