import {describe, expect, it} from 'vitest';

import {EMPTY_LIVE_LAP_HISTORY, toLapDrivers, updateLiveLapHistory} from './liveLaps';
import type {ITimingBoard} from './LiveTiming';

describe('liveLaps', () => {
	it('records completed laps for human players only and ignores AI', () => {
		const poll1: ITimingBoard = {
			stage: 'Race1',
			trackId: '100',
			raceLength: 10,
			cars: [
				{position: 1, name: 'McRod', player: true, lap: 2, lastLap: 82500},
				{position: 2, name: 'Fernando Alonso (AI)', player: false, lap: 2, lastLap: 83100},
			],
		};
		const poll2: ITimingBoard = {
			stage: 'Race1',
			trackId: '100',
			raceLength: 10,
			cars: [
				{position: 1, name: 'McRod', player: true, lap: 3, lastLap: 81900},
				{position: 2, name: 'mbr_gt', player: true, lap: 2, lastLap: 84000},
			],
		};

		let history = updateLiveLapHistory(EMPTY_LIVE_LAP_HISTORY, poll1);
		history = updateLiveLapHistory(history, poll2);

		expect(toLapDrivers(history)).toEqual([
			{
				name: 'McRod',
				laps: [
					{lap: 1, time: 82500, valid: true, s1: null, s2: null, s3: null, position: null},
					{lap: 2, time: 81900, valid: true, s1: null, s2: null, s3: null, position: null},
				],
			},
			{
				name: 'mbr_gt',
				laps: [
					{lap: 1, time: 84000, valid: true, s1: null, s2: null, s3: null, position: null},
				],
			},
		]);
	});

	it('resets accumulated laps when the session changes', () => {
		const race1: ITimingBoard = {
			stage: 'Race1',
			trackId: '100',
			raceLength: 10,
			cars: [{position: 1, name: 'McRod', player: true, lap: 2, lastLap: 82500}],
		};
		const qualy: ITimingBoard = {
			stage: 'Qualifying1',
			trackId: '100',
			raceLength: 10,
			cars: [{position: 1, name: 'McRod', player: true, lap: 2, lastLap: 80100}],
		};

		let history = updateLiveLapHistory(EMPTY_LIVE_LAP_HISTORY, race1);
		history = updateLiveLapHistory(history, qualy);

		expect(toLapDrivers(history)).toEqual([
			{
				name: 'McRod',
				laps: [{lap: 1, time: 80100, valid: true, s1: null, s2: null, s3: null, position: null}],
			},
		]);
	});
});
