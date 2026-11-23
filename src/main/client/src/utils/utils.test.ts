import {describe, expect, it} from 'vitest';
import {calculatePoints} from './pointsCalculator';
import {sanitizeString} from './stringUtils';
import {parsePositions, parseTeams, formatTeams, parseAdjustments, formatAdjustments} from './scoringPresets';
import {computeStandings, droppedRounds} from './standings';
import {toCsv} from './csv';

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

describe('standings', () => {
	const race = (day: number, points: Record<string, number>) => ({
		startDate: day,
		raceResults: Object.entries(points).map(([driverName, totalPoints]) => ({driverName, totalPoints})),
	});
	const races = [race(3, {A: 10, B: 25}), race(1, {A: 25, B: 18, C: 4}), race(2, {A: 18, B: 0})];

	it('orders rounds by date and totals each driver', () => {
		const {rounds, rows} = computeStandings(races);
		expect(rounds.map(r => r.startDate)).toEqual([1, 2, 3]);
		expect(rows.map(r => [r.name, r.total])).toEqual([['A', 53], ['B', 43], ['C', 4]]);
		expect(rows[2].perRound).toEqual([4, null, null]);
	});

	it('drops the lowest rounds, counting a missed round as 0 first', () => {
		const {rows} = computeStandings(races, {type: 'default', positions: [], poleBonus: 0, fastestLapBonus: 0, dropRounds: 1});
		const byName = Object.fromEntries(rows.map(r => [r.name, r]));
		expect(byName.A.total).toBe(43); // 25 + 18, the 10 is dropped
		expect(byName.A.dropped).toEqual([false, false, true]);
		expect(byName.B.total).toBe(43); // The 0 is dropped
		expect(byName.C.total).toBe(4); // A missed round is dropped before the 4
		expect(byName.C.dropped).toEqual([false, true, false]);
	});

	it('does not drop anything when the season is not longer than the drops', () => {
		expect(droppedRounds([5, 1], 2)).toEqual([false, false]);
		expect(droppedRounds([5, 1, 9], 0)).toEqual([false, false, false]);
		expect(droppedRounds([5, 5, 9], 1)).toEqual([true, false, false]);
	});

	it('applies adjustments after drops and builds team totals', () => {
		const scoring = {
			type: 'default' as const, positions: [], poleBonus: 0, fastestLapBonus: 0,
			adjustments: [{driver: 'A', points: -5}, {driver: 'A', points: 2}],
			teams: [{name: 'Red', drivers: ['A', 'C']}, {name: 'Blue', drivers: ['B', 'Nobody']}],
		};
		const {rows, teams} = computeStandings(races, scoring);
		expect(rows.find(r => r.name === 'A')?.total).toBe(50);
		expect(rows.find(r => r.name === 'A')?.adjustment).toBe(-3);
		expect(teams.map(t => [t.name, t.total])).toEqual([['Red', 54], ['Blue', 43]]);
	});

	it('copes with no races', () => {
		expect(computeStandings([])).toEqual({rounds: [], rows: [], teams: []});
	});
});

describe('csv', () => {
	it('quotes cells and escapes quotes', () => {
		expect(toCsv([['a', 'b,c', 'say "hi"'], [1, null, undefined]])).toBe('a,"b,c","say ""hi"""\r\n1,,\r\n');
	});

	it('defuses spreadsheet formulas in player-chosen names', () => {
		expect(toCsv([['=HYPERLINK("http://x")', '+1', '-2', '@SUM(A1)', 'normal']])).toBe('"\'=HYPERLINK(""http://x"")",\'+1,\'-2,\'@SUM(A1),normal\r\n');
	});
});

describe('team and adjustment text', () => {
	it('parses and formats teams', () => {
		const teams = parseTeams('Red Team: Ayrton S, Alain P\nBlue: Nigel M');
		expect(teams).toEqual([{name: 'Red Team', drivers: ['Ayrton S', 'Alain P']}, {name: 'Blue', drivers: ['Nigel M']}]);
		expect(parseTeams(formatTeams(teams ?? []))).toEqual(teams);
		expect(parseTeams('')).toEqual([]);
	});

	it('rejects malformed teams and drivers in two teams', () => {
		expect(parseTeams('No colon here')).toBeNull();
		expect(parseTeams('Empty:')).toBeNull();
		expect(parseTeams('A: X\nB: X')).toBeNull();
	});

	it('parses adjustments with optional notes', () => {
		expect(parseAdjustments('Ayrton S, -5, track limits\nAlain P, +3')).toEqual([
			{driver: 'Ayrton S', points: -5, note: 'track limits'},
			{driver: 'Alain P', points: 3, note: ''},
		]);
		expect(parseAdjustments(formatAdjustments([{driver: 'A', points: -2, note: 'x, y'}]))).toEqual([{driver: 'A', points: -2, note: 'x, y'}]);
	});

	it('rejects malformed adjustments', () => {
		for (const bad of ['no commas', 'A, abc', 'A, 99999', ', 5', 'A, 1.5']) {
			expect(parseAdjustments(bad)).toBeNull();
		}
	});
});
