// Penalties an admin adds to a race afterwards. Applied when results are shown, so the stored race stays as the game server recorded it.
export interface IPenalty {
	driver: string,
	seconds: number,
	disqualify: boolean,
	note: string,
}

const lapsOf = (r: any) => r.attributes.Lap ?? 0;
const timeOf = (r: any) => r.attributes.TotalTime ?? 0;

// Returns the race results (the game server's `stages.race1.results` shape) with time penalties added to the total
// time, disqualified drivers marked, and the order and positions recomputed. Without penalties it returns the same list.
export const applyPenalties = (results: Array<any>, penalties?: Array<IPenalty> | null): Array<any> => {
	if (!penalties || penalties.length === 0) {
		return results;
	}

	const byDriver = new Map(penalties.map(p => [p.driver, p]));
	const adjusted = results.map((r, index) => {
		const penalty = byDriver.get(r.name);
		if (!penalty) {
			return {r, index};
		}

		const attributes = {...r.attributes};
		if (penalty.disqualify) {
			attributes.State = 'Disqualified';
		} else if (attributes.State === 'Finished' && timeOf(r) > 0) {
			attributes.TotalTime = timeOf(r) + (penalty.seconds * 1000);
		}

		return {r: {...r, attributes, penalty}, index};
	});

	// Finishers by distance then time; everyone else (retired, disqualified) keeps the order the game server gave them
	const finishers = adjusted.filter(x => x.r.attributes.State === 'Finished')
		.sort((a, b) => (lapsOf(b.r) - lapsOf(a.r)) || (timeOf(a.r) - timeOf(b.r)) || (a.index - b.index));
	const others = adjusted.filter(x => x.r.attributes.State !== 'Finished');

	return [...finishers, ...others].map((x, i) => ({...x.r, attributes: {...x.r.attributes, RacePosition: i + 1}}));
};
