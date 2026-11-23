export interface IScoring {
	type: 'default' | 'table',
	positions: Array<number>, // Points for 1st, 2nd, 3rd...
	poleBonus: number,
	fastestLapBonus: number,
}

// Sessions without a scoring table use the original built-in formula below.
export interface IPointsOptions {
	tookPole?: boolean,
	fastestLap?: boolean,
	scoring?: IScoring | null,
}

export const calculatePoints = (finishPlace:number, numberOfPlayers:number, options: IPointsOptions = {}) => {
	const {tookPole, fastestLap, scoring} = options;
	if (scoring && scoring.type === 'table') {
		const base = scoring.positions[finishPlace - 1] ?? 0;
		return base + (tookPole ? scoring.poleBonus : 0) + (fastestLap ? scoring.fastestLapBonus : 0);
	}

	const pointsPositions = Math.round(numberOfPlayers / 2); // Assign points for the 1st half of the finish places
	const pointsPositionsBeat = pointsPositions - finishPlace;

	let points = pointsPositionsBeat < 0 ? 0 : pointsPositionsBeat * 2;

	const firstPlaceBonus = 0.4;
	const secondPlaceBonus = 0.1;
	const thirdPlaceBonus = 0.075;

	switch (finishPlace) {
		case 1:
			points += points * firstPlaceBonus;
			break;
		case 2:
			points += points * secondPlaceBonus;
			break;
		case 3:
			points += points * thirdPlaceBonus;
			break;
		case pointsPositions: // Finished in the last points position
			points = 1;
			break;
		default:
			break;
	}

	points += (tookPole ? 1 : 0);
	points += (fastestLap ? 1 : 0);

	return Math.round(points);
};

