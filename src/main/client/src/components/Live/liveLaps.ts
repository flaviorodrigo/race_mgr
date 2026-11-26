import {ILapDriver} from '../Leaderboard/interfaces';
import type {ITimingBoard, ITimingCar} from './LiveTiming';

export interface ILiveLapHistory {
	sessionKey: string;
	maxLeaderLap: number;
	lapsByDriver: Record<string, Record<number, number>>;
}

export const EMPTY_LIVE_LAP_HISTORY: ILiveLapHistory = {
	sessionKey: '',
	maxLeaderLap: 0,
	lapsByDriver: {},
};

const completedLapFor = (car: ITimingCar, raceLength: number | undefined, prevLaps: Record<number, number>): number | null => {
	if (typeof car.lap !== 'number' || car.lap < 1) {
		return null;
	}

	if (car.lap > 1) {
		if (car.state === 'Finished' && raceLength && car.lap === raceLength
			&& prevLaps[car.lap - 1] !== undefined && prevLaps[car.lap - 1] !== car.lastLap) {
			return car.lap;
		}

		return car.lap - 1;
	}

	return car.state === 'Finished' ? car.lap : null;
};

const sessionKeyFor = (board: ITimingBoard) => `${board.stage ?? ''}|${board.trackId ?? ''}|${board.raceLength ?? ''}`;

// Accumulate completed lap times for non-AI players across live-timing polls, resetting on session change
export const updateLiveLapHistory = (prev: ILiveLapHistory, board: ITimingBoard | null): ILiveLapHistory => {
	if (!board) {
		return prev;
	}

	const sessionKey = sessionKeyFor(board);
	const leaderLap = board.cars.find(c => c.position === 1)?.lap ?? 0;
	const reset = prev.sessionKey !== sessionKey || (prev.maxLeaderLap > 1 && leaderLap > 0 && leaderLap < prev.maxLeaderLap);
	const maxLeaderLap = reset ? leaderLap : Math.max(prev.maxLeaderLap, leaderLap);
	const nextLaps: Record<string, Record<number, number>> = reset ? {} : {...prev.lapsByDriver};
	let changed = reset || maxLeaderLap !== prev.maxLeaderLap;

	for (const c of board.cars) {
		if (!c.player || typeof c.lastLap !== 'number' || c.lastLap <= 0) {
			continue;
		}

		const driverLaps = nextLaps[c.name] ?? {};
		const completedLap = completedLapFor(c, board.raceLength, driverLaps);
		if (completedLap !== null && driverLaps[completedLap] !== c.lastLap) {
			nextLaps[c.name] = {...driverLaps, [completedLap]: c.lastLap};
			changed = true;
		}
	}

	return changed ? {sessionKey, maxLeaderLap, lapsByDriver: nextLaps} : prev;
};

// Convert accumulated live lap history into ILapDriver[] so LapChart / lapSeries can render it directly
export const toLapDrivers = (history: ILiveLapHistory): Array<ILapDriver> => Object.entries(history.lapsByDriver).map(([name, laps]) => ({
	name,
	laps: Object.entries(laps)
		.map(([lap, time]) => ({lap: Number(lap), time, valid: true, s1: null, s2: null, s3: null, position: null}))
		.sort((a, b) => a.lap - b.lap),
}));
