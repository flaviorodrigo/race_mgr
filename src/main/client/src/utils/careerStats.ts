// All-time records over every counted race: per driver career totals, and the quickest lap at each track.
// Points are left out on purpose: they depend on each championship's own scoring.
export interface ICareerRace {
	startDate: number,
	trackName: string,
	voided?: boolean,
	finished?: boolean,
	polePosition?: string,
	fastestLap?: string,
	raceResults?: Array<{driverName: string, isPlayer?: boolean, finishPosition: number, state?: string, fastestLapTime: number, vehicleId?: string}>,
	qualifyingResults?: Array<{driverName: string, bestQualifyingTime: number}>,
}

export interface ICareerRow {
	name: string,
	races: number,
	wins: number,
	podiums: number,
	poles: number,
	fastestLaps: number,
	dnfs: number,
	disqualifications: number,
	bestFinish: number,
	averageFinish: number,
	lastRace: number,
}

export interface ITrackRecord {
	trackId: string,
	driver: string,
	time: number,
	vehicleId?: string,
	date: number,
	qualifying: boolean,
}

const counted = (races: Array<ICareerRace>) => races.filter(r => r.finished !== false && !r.voided);

// AI drivers are left out; a driver the game server doesn't flag is counted
const isDriver = (result: {isPlayer?: boolean}) => result.isPlayer !== false;

export const careerStats = (allRaces: Array<ICareerRace>): Array<ICareerRow> => {
	const rows = new Map<string, ICareerRow & {finishSum: number}>();
	const row = (name: string) => {
		let r = rows.get(name);
		if (!r) {
			r = {name, races: 0, wins: 0, podiums: 0, poles: 0, fastestLaps: 0, dnfs: 0, disqualifications: 0, bestFinish: Number.POSITIVE_INFINITY, averageFinish: 0, lastRace: 0, finishSum: 0};
			rows.set(name, r);
		}

		return r;
	};

	for (const race of counted(allRaces)) {
		for (const result of race.raceResults ?? []) {
			if (!isDriver(result)) {
				continue;
			}

			const r = row(result.driverName);
			r.races += 1;
			r.lastRace = Math.max(r.lastRace, race.startDate);
			r.finishSum += result.finishPosition;
			if (result.state === 'Disqualified') {
				r.disqualifications += 1;
			} else if (result.state === 'Retired') {
				r.dnfs += 1;
			} else {
				r.bestFinish = Math.min(r.bestFinish, result.finishPosition);
				r.wins += result.finishPosition === 1 ? 1 : 0;
				r.podiums += result.finishPosition <= 3 ? 1 : 0;
			}

			if (result.driverName === race.polePosition) {
				r.poles += 1;
			}

			if (result.driverName === race.fastestLap) {
				r.fastestLaps += 1;
			}
		}
	}

	return [...rows.values()]
		.map(({finishSum, ...r}) => ({...r, bestFinish: Number.isFinite(r.bestFinish) ? r.bestFinish : 0, averageFinish: r.races ? finishSum / r.races : 0}))
		.sort((a, b) => b.wins - a.wins || b.podiums - a.podiums || b.races - a.races || a.name.localeCompare(b.name));
};

// The quickest lap ever set at each track, in a race or in qualifying; disqualified drivers don't count
export const trackRecords = (allRaces: Array<ICareerRace>): Array<ITrackRecord> => {
	const best = new Map<string, ITrackRecord>();
	const consider = (record: ITrackRecord) => {
		const current = best.get(record.trackId);
		if (record.time > 0 && (!current || record.time < current.time)) {
			best.set(record.trackId, record);
		}
	};

	for (const race of counted(allRaces)) {
		for (const r of race.raceResults ?? []) {
			if (isDriver(r) && r.state !== 'Disqualified') {
				consider({trackId: String(race.trackName), driver: r.driverName, time: r.fastestLapTime, vehicleId: r.vehicleId, date: race.startDate, qualifying: false});
			}
		}

		for (const q of race.qualifyingResults ?? []) {
			consider({trackId: String(race.trackName), driver: q.driverName, time: q.bestQualifyingTime, date: race.startDate, qualifying: true});
		}
	}

	return [...best.values()];
};
