import {IRace} from './interfaces';
import {ITrackData} from '../../interfaces/asm2Interfaces';

export interface IRacesFilter {
	startDate: number;
	endDate: number;
	trackId: string;
}

// Display name for a track id, falling back to the raw id
export const trackLabel = (trackId: string, tracks: Array<ITrackData>): string =>
	tracks.find(t => String(t.id) === String(trackId))?.name.replaceAll('_', ' ') ?? trackId;

// Unique tracks present in the loaded races, sorted alphabetically by display name
export const availableTracks = (races: Array<IRace>, tracks: Array<ITrackData>): Array<{id: string; name: string}> => {
	const seen = new Set<string>();
	const list: Array<{id: string; name: string}> = [];
	for (const r of races) {
		if (!r.trackName || seen.has(r.trackName)) {
			continue;
		}

		seen.add(r.trackName);
		list.push({id: r.trackName, name: trackLabel(r.trackName, tracks)});
	}

	return list.sort((a, b) => a.name.localeCompare(b.name));
};

// Filter finished races by date range and track, newest first
export const filterAndSortRaces = (races: Array<IRace>, filter: IRacesFilter): Array<IRace> => {
	const endBound = filter.endDate > 0 ? filter.endDate : Infinity;
	return races
		.filter(r => r.startDate >= filter.startDate && r.endDate <= endBound)
		.filter(r => !filter.trackId || filter.trackId === 'All' || r.trackName === filter.trackId)
		.sort((a, b) => b.startDate - a.startDate);
};
