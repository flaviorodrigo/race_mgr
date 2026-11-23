import {IScoring} from './pointsCalculator';

// The few fields of a race this needs, so it can be tested without a whole race
export interface IScoredRace {
	startDate: number,
	raceResults?: Array<{driverName: string, totalPoints: number}>,
}

export interface IStandingRow {
	name: string,
	perRound: Array<number | null>, // Null = didn't take part
	dropped: Array<boolean>, // Rounds that don't count
	adjustment: number,
	total: number,
}

export interface ITeamRow {
	name: string,
	drivers: Array<string>,
	total: number,
}

export interface IStandings {
	rounds: Array<IScoredRace>,
	rows: Array<IStandingRow>,
	teams: Array<ITeamRow>,
}

// Which rounds are dropped: the `count` lowest scores (a missed round counts as 0 and goes first; ties drop the
// earlier round). Nothing is dropped unless the season is longer than the number of drops.
export const droppedRounds = (scores: Array<number | null>, count: number): Array<boolean> => {
	const dropped = scores.map(() => false);
	if (count <= 0 || scores.length <= count) {
		return dropped;
	}

	scores
		.map((score, index) => ({score: score ?? 0, index}))
		.sort((a, b) => a.score - b.score || a.index - b.index)
		.slice(0, count)
		.forEach(entry => {
			dropped[entry.index] = true;
		});
	return dropped;
};

// Championship standings for a set of races (voided races must already be left out): per round points, dropped
// rounds, manual adjustments, totals, and team totals. Highest total first, ties by name.
export const computeStandings = (races: Array<IScoredRace>, scoring?: IScoring | null): IStandings => {
	const rounds = [...races].sort((a, b) => a.startDate - b.startDate);
	const byDriver = new Map<string, Array<number | null>>();

	rounds.forEach((race, roundIndex) => {
		(race.raceResults ?? []).forEach(result => {
			if (!byDriver.has(result.driverName)) {
				byDriver.set(result.driverName, rounds.map(() => null));
			}

			(byDriver.get(result.driverName) as Array<number | null>)[roundIndex] = result.totalPoints ?? 0;
		});
	});

	const adjustments = new Map<string, number>();
	(scoring?.adjustments ?? []).forEach(a => adjustments.set(a.driver, (adjustments.get(a.driver) ?? 0) + a.points));

	const rows: Array<IStandingRow> = Array.from(byDriver, ([name, perRound]) => {
		const dropped = droppedRounds(perRound, scoring?.dropRounds ?? 0);
		const counted = perRound.reduce<number>((sum, p, i) => sum + (dropped[i] ? 0 : p ?? 0), 0);
		const adjustment = adjustments.get(name) ?? 0;
		return {name, perRound, dropped, adjustment, total: counted + adjustment};
	}).sort((a, b) => b.total - a.total || a.name.toLowerCase().localeCompare(b.name.toLowerCase()));

	const totals = new Map(rows.map(r => [r.name, r.total]));
	const teams: Array<ITeamRow> = (scoring?.teams ?? [])
		.map(t => ({name: t.name, drivers: t.drivers, total: t.drivers.reduce((sum, d) => sum + (totals.get(d) ?? 0), 0)}))
		.sort((a, b) => b.total - a.total || a.name.toLowerCase().localeCompare(b.name.toLowerCase()));

	return {rounds, rows, teams};
};
