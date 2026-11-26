import {describe, expect, it} from 'vitest';

import {availableTracks, filterAndSortRaces} from './racesFilter';
import type {IRace} from './interfaces';
import type {ITrackData} from '../../interfaces/asm2Interfaces';

const makeRace = (overrides: Partial<IRace>): IRace => ({
	raceId: 'r1',
	startDate: 1000,
	endDate: 1500,
	finished: true,
	gridSize: 10,
	laps: 5,
	aiStrength: 90,
	trackName: '100',
	winner: 'McRod',
	polePosition: 'McRod',
	fastestLap: 'McRod',
	participants: [],
	raceResults: [],
	qualifyingResults: [],
	...overrides,
});

describe('racesFilter', () => {
	it('filters by date range and track and sorts newest first', () => {
		const races = [
			makeRace({raceId: 'old', startDate: 100, endDate: 200, trackName: '100'}),
			makeRace({raceId: 'mid', startDate: 500, endDate: 600, trackName: '200'}),
			makeRace({raceId: 'new', startDate: 900, endDate: 1000, trackName: '100'}),
		];

		const filtered = filterAndSortRaces(races, {startDate: 100, endDate: 1000, trackId: '100'});
		expect(filtered.map(r => r.raceId)).toEqual(['new', 'old']);
	});

	it('returns unique sorted track options from the loaded races', () => {
		const races = [
			makeRace({raceId: '1', trackName: '200'}),
			makeRace({raceId: '2', trackName: '100'}),
			makeRace({raceId: '3', trackName: '200'}),
		];
		const tracks = [
			{id: '100', name: 'Spa_Francorchamps', gridsize: 32},
			{id: '200', name: 'Interlagos_GP', gridsize: 32},
		] as Array<ITrackData>;

		expect(availableTracks(races, tracks)).toEqual([
			{id: '200', name: 'Interlagos GP'},
			{id: '100', name: 'Spa Francorchamps'},
		]);
	});
});
