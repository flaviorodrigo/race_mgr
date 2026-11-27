import {ILapDriver} from '../components/Leaderboard/interfaces';

export interface ISectorRow {
	name: string,
	state: string | null,
	validLaps: number,
	bestLap: number | null,
	s1: number | null,
	s2: number | null,
	s3: number | null,
	theoretical: number | null, // The best three sectors strung together
	gap: number | null, // How much quicker the best lap could have been
}

const best = (values: Array<number | null | undefined>): number | null => {
	const usable = values.filter((v): v is number => typeof v === 'number' && v > 0);
	return usable.length ? Math.min(...usable) : null;
};

const lapsOf = (driver: ILapDriver) => (Array.isArray(driver?.laps) ? driver.laps : []);

// Best lap, best sectors and the theoretical best lap for each driver, from their valid laps only
export const sectorRows = (drivers: Array<ILapDriver> = []): Array<ISectorRow> => (Array.isArray(drivers) ? drivers : []).map(driver => {
	const valid = lapsOf(driver).filter(l => l.valid);
	const s1 = best(valid.map(l => l.s1));
	const s2 = best(valid.map(l => l.s2));
	const s3 = best(valid.map(l => l.s3));
	const bestLap = best(valid.map(l => l.time));
	const theoretical = s1 !== null && s2 !== null && s3 !== null ? s1 + s2 + s3 : null;
	return {
		name: driver.name,
		state: driver.state ?? null,
		validLaps: valid.filter(l => typeof l.time === 'number' && l.time > 0).length,
		bestLap, s1, s2, s3, theoretical,
		gap: bestLap !== null && theoretical !== null ? bestLap - theoretical : null,
	};
}).sort((a, b) => (a.bestLap ?? Infinity) - (b.bestLap ?? Infinity));

export interface ISeries {
	name: string,
	points: Array<{lap: number, time: number}>,
}

// Lap times for a chart: valid laps only, and nothing slower than 12% over the typical lap, so one spin or pit
// stop doesn't flatten everybody else's line.
export const lapSeries = (drivers: Array<ILapDriver> = []): {series: Array<ISeries>, min: number, max: number} => {
	const list = Array.isArray(drivers) ? drivers : [];
	const all = list.flatMap(d => lapsOf(d).filter(l => l.valid && typeof l.time === 'number' && l.time > 0).map(l => l.time as number)).sort((a, b) => a - b);
	if (all.length === 0) {
		return {series: [], min: 0, max: 0};
	}

	const median = all[Math.floor(all.length / 2)];
	const limit = median * 1.12;
	const series = list.map(d => ({
		name: d.name,
		points: lapsOf(d).filter(l => l.valid && typeof l.time === 'number' && l.time > 0 && l.time <= limit).map(l => ({lap: l.lap, time: l.time as number})),
	})).filter(s => s.points.length > 0);
	const times = series.flatMap(s => s.points.map(p => p.time));
	return {series, min: Math.min(...times), max: Math.max(...times)};
};

export const formatLap = (ms: number | null) => {
	if (ms === null || ms === undefined) {
		return '';
	}

	const minutes = Math.floor(ms / 60000);
	return `${minutes}:${((ms - (minutes * 60000)) / 1000).toFixed(3).padStart(6, '0')}`;
};

export const formatSector = (ms: number | null) => (ms === null || ms === undefined ? '' : (ms / 1000).toFixed(3));
