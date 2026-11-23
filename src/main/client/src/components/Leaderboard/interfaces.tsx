export interface ILap {
	lap: number,
	time: number | null,
	s1: number | null,
	s2: number | null,
	s3: number | null,
	valid: boolean,
	position: number | null,
}

// Per-lap data, only present when the server runs the ams2_stats addon
export interface ILapDriver {
	name: string,
	state?: string | null,
	best_s1?: number | null,
	best_s2?: number | null,
	best_s3?: number | null,
	laps: Array<ILap>,
}

export interface IRace {
	drivers?: Array<ILapDriver>,
	qualifyingDrivers?: Array<ILapDriver>,
	raceId?: string,
	voided?: boolean,
	sessionId?: string,
	trackName: string,
	startDate: number,
	endDate: number,
	gridSize: number,
	laps: number,
	aiStrength: number,
	finished: boolean
	polePosition: string,
	fastestLap: string,
	winner: string,
	participants: Array<string>,
	raceResults: Array<IRaceResults>,
	qualifyingResults: Array<IRaceResults>,

}

export interface IRaceResults {
	participantid: string,
	driverName: string,
	isPlayer: boolean,
	finishPosition: number,
	vehicleId: string,
	bestQualifyingTime: number,
	fastestLapTime: number,
	totalTime: number,
	totalPoints: number,
	tookPole: boolean,
	hadFastestLap: boolean,
	state?: string,
}
