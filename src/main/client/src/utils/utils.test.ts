import {describe, expect, it} from 'vitest';
import {calculatePoints} from './pointsCalculator';
import {sanitizeString} from './stringUtils';

describe('calculatePoints', () => {
	it('gives the winner a 40% bonus over a half-field points scale', () => {
		// 10 players: points for the first 5 places; winner = (5 - 1) * 2 = 8, +40% = 11.2
		expect(calculatePoints(1, 10)).toBe(11);
	});

	it('gives nothing outside the points positions', () => {
		expect(calculatePoints(9, 10)).toBe(0);
	});

	it('adds pole and fastest lap bonuses', () => {
		expect(calculatePoints(9, 10, true, true)).toBe(2);
	});
});

describe('sanitizeString', () => {
	it('strips everything but letters, digits and spaces', () => {
		expect(sanitizeString('Race #1: <b>Spa</b>!')).toBe('Race 1 bSpab');
	});
});
