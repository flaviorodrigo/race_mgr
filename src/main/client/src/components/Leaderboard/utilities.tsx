import _ from 'lodash';

import {calculatePoints, IScoring} from '../../utils/pointsCalculator';
import {applyPenalties} from '../../utils/penalties';
import {IRace, IRaceResults} from './interfaces';

import RaceMgrApi from '../../api/RaceMgrApi';

export const fetchRaceData = async (currentSessionId?:string, currentRotationId?: string, scoring?: IScoring | null) => {
	const rmApi = new RaceMgrApi();
	try {
		if (!currentSessionId) {
			return [];
		}

		const sessionId = currentSessionId === 'All' ? '' : currentSessionId;
		const rotationId = currentRotationId === 'All' ? '' : currentRotationId;

		const requestConfig = {
			url: `${rmApi.getBaseApiUrl()}/race/events?raceSessionId=${sessionId}&raceRotationId=${rotationId}&page=0&limit=10000`,
			method: 'get',
		};

		const apiResults = await rmApi.makeApiCall(requestConfig);
		const {data: apiData} = apiResults;
		const raceData = apiData.data.content || null;

		let sessionResults;
		if (raceData === null) {
			sessionResults = [];
		} else {
			sessionResults = mapRaceDataToRaceResultsObject(raceData, scoring);
		}

		return sessionResults;
	} catch (err) {
		console.log('Error', err);
	}
};

export const fetchTrackData = async () => {
	const rmApi = new RaceMgrApi();
	try {
		const requestConfig = {
			url: `${rmApi.getBaseApiUrl()}/gameserver/tracks`,
			method: 'get',
		};

		const apiResults = await rmApi.makeApiCall(requestConfig);
		const {data: apiData} = apiResults;
		const trackData = apiData.data || null;
		return trackData;
	} catch (err) {
		console.log('Error', err);
	}
};

export const fetchVehicleData = async () => {
	const rmApi = new RaceMgrApi();
	try {
		const requestConfig = {
			url: `${rmApi.getBaseApiUrl()}/gameserver/vehicles`,
			method: 'get',
		};

		const apiResults = await rmApi.makeApiCall(requestConfig);
		const {data: apiData} = apiResults;
		const vehicleData = apiData.data || null;
		return vehicleData;
	} catch (err) {
		console.log('Error', err);
	}
};

export const mapRaceDataToRaceResultsObject = (data:Array<any>, scoring?: IScoring | null):Array<IRace> => {
	const raceResults:Array<IRace> = [];

	const finishedRaces = data.filter(d => d.finished === true && d.stages.race1);

	for (const d in finishedRaces) {
		const thisRace = finishedRaces[d];
		// Time penalties and disqualifications an admin added after the race
		const race1Results = applyPenalties(thisRace.stages.race1?.results ?? [], thisRace.penalties);
		const fastestLapTimes = race1Results.map(result => ({
			driverName: result.name,
			fastestLapTime: result.attributes.FastestLapTime,
			disqualified: result.attributes.State === 'Disqualified',
		}));

		const winnerLaps = Math.max(0, ...race1Results.map((x:any) => x.attributes.Lap ?? 0));
		const polePositionName = _.head(thisRace.stages.qualifying1?.results)?.name;
		const winnerName = _.head(race1Results)?.name;
		const fastestLapName = _.head(_.orderBy(fastestLapTimes?.filter(fl => fl.fastestLapTime > 0 && !fl.disqualified), ['fastestLapTime'], ['asc']))?.driverName;

		const participantList:Array<string> = [];
		for (const p in thisRace.participants) {
			const thisP = thisRace.participants[p];
			participantList.push(thisP.Name);
		}

		const raceResult:IRace = {
			raceId: thisRace.race_id,
			voided: thisRace.voided === true,
			videoUrl: thisRace.videoUrl ?? undefined,
			penalties: thisRace.penalties ?? [],
			sessionId: thisRace.race_session_id,
			trackName: thisRace.setup.TrackId,
			startDate: thisRace.start_time,
			endDate: thisRace.end_time,
			finished: thisRace.finished,
			gridSize: Object.keys(thisRace.participants).length,
			laps: thisRace.setup.RaceLength,
			aiStrength: thisRace.setup.OpponentDifficulty,
			participants: participantList,
			// (A race result has no qualifying fields)
			raceResults: race1Results.map((r:any) => ({
				driverName: r.name,
				isPlayer: r.is_player,
				finishPosition: r.attributes.RacePosition,
				vehicleId: r.attributes.VehicleId,
				fastestLapTime: r.attributes.FastestLapTime,
				totalTime: r.attributes.TotalTime,
				state: r.attributes.State,
				// A disqualified driver, or one who retired before 90% of the winner's distance, keeps their place in the
				// result but scores nothing
				totalPoints: r.attributes.State === 'Disqualified' || (r.attributes.State === 'Retired' && r.attributes.Lap < winnerLaps * 0.9)
					? 0
					: calculatePoints(r.attributes.RacePosition, Object.keys(thisRace.participants).length, {tookPole: r.name === polePositionName, fastestLap: r.name === fastestLapName, scoring}),
				hadFastestLap: r.name === fastestLapName,
				penaltySeconds: r.penalty && !r.penalty.disqualify ? r.penalty.seconds : undefined,
				penaltyNote: r.penalty?.note || undefined,
			})) as Array<IRaceResults>,
			qualifyingResults: thisRace.stages.qualifying1?.results.map((r:any) => ({
				driverName: r.name,
				bestQualifyingTime: r.attributes.FastestLapTime,
				tookPole: r.name === polePositionName,
			})),
			drivers: thisRace.stages.race1?.drivers ?? [],
			qualifyingDrivers: thisRace.stages.qualifying1?.drivers ?? [],
			polePosition: polePositionName,
			fastestLap: fastestLapName,
			winner: winnerName,

		};
		raceResults.push(raceResult);
	}

	return raceResults;
};
