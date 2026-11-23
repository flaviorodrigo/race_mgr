/* eslint-disable camelcase */
// The game server's own field names (start_time, race_id...) are used as they are
import {describe, expect, it} from 'vitest';
import {mapRaceDataToRaceResultsObject} from './utilities';

const result = (name: string, position: number, attributes: Record<string, unknown> = {}) => ({
	name,
	attributes: {RacePosition: position, FastestLapTime: 100000 + position, TotalTime: 900000, Lap: 17, State: 'Finished', ...attributes},
});

const race = (race1: Array<unknown>, qualifying: Array<unknown> | null = [result('A', 1)]) => ({
	finished: true,
	start_time: '1', end_time: '2', race_id: 'abc',
	participants: {0: {Name: 'A'}, 1: {Name: 'B'}, 2: {Name: 'C'}, 3: {Name: 'D'}},
	setup: {TrackId: 1, RaceLength: 17},
	stages: {race1: {results: race1}, ...(qualifying ? {qualifying1: {results: qualifying}} : {})},
});

const points = (r: ReturnType<typeof mapRaceDataToRaceResultsObject>[number]) => Object.fromEntries(r.raceResults.map(x => [x.driverName, x.totalPoints]));
const table = {type: 'table' as const, positions: [25, 18, 15, 12], poleBonus: 0, fastestLapBonus: 0};

describe('mapping race results', () => {
	it('gives a disqualified driver their place but no points', () => {
		const [r] = mapRaceDataToRaceResultsObject([race([result('A', 1), result('B', 2, {State: 'Disqualified'}), result('C', 3)])], table);
		expect(r.raceResults.map(x => x.finishPosition)).toEqual([1, 2, 3]);
		expect(points(r)).toEqual({A: 25, B: 0, C: 15});
	});

	it('scores a retirement only if they covered 90% of the winners distance', () => {
		const [r] = mapRaceDataToRaceResultsObject([race([
			result('A', 1), result('B', 2, {State: 'Retired', Lap: 16}), result('C', 3, {State: 'Retired', Lap: 10}),
		])], table);
		expect(points(r)).toEqual({A: 25, B: 18, C: 0});
	});

	it('copes with a race that has no qualifying session', () => {
		const [r] = mapRaceDataToRaceResultsObject([race([result('A', 1), result('B', 2)], null)], table);
		expect(r.polePosition).toBeUndefined();
		expect(points(r)).toEqual({A: 25, B: 18});
	});

	it('does not give the fastest lap bonus to a disqualified driver', () => {
		const [r] = mapRaceDataToRaceResultsObject([race([result('A', 1), result('B', 2, {State: 'Disqualified', FastestLapTime: 90000})])],
			{...table, fastestLapBonus: 2});
		expect(r.fastestLap).toBe('A');
		expect(points(r)).toEqual({A: 27, B: 0});
	});

	it('skips races that are not finished and keeps the race id', () => {
		expect(mapRaceDataToRaceResultsObject([{...race([result('A', 1)]), finished: false}], table)).toEqual([]);
		expect(mapRaceDataToRaceResultsObject([race([result('A', 1)])], table)[0].raceId).toBe('abc');
	});
});
