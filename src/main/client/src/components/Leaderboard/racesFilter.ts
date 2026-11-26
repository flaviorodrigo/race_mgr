import {IRace} from './interfaces';
import {ITrackData} from '../../interfaces/asm2Interfaces';

export interface IRacesFilter {
	startDate: number;
	endDate: number;
	trackId: string;
}

// Display name for a track id, falling back to the stringified id
export const trackLabel = (trackId: string | number | undefined, tracks: Array<ITrackData>): string => {
	if (trackId === undefined || trackId === null) {
		return '';
	}

	const match = tracks.find(t => String(t.id) === String(trackId));
	return match?.name ? String(match.name).replaceAll('_', ' ') : String(trackId);
};

// Unique tracks present in the loaded races, sorted alphabetically by display name
export const availableTracks = (races: Array<IRace>, tracks: Array<ITrackData>): Array<{id: string; name: string}> => {
	const seen = new Set<string>();
	const list: Array<{id: string; name: string}> = [];
	for (const r of races) {
		if (r.trackName === undefined || r.trackName === null || r.trackName === '') {
			continue;
		}

		const id = String(r.trackName);
		if (seen.has(id)) {
			continue;
		}

		seen.add(id);
		list.push({id, name: trackLabel(id, tracks)});
	}

	return list.sort((a, b) => a.name.localeCompare(b.name));
};

// Filter finished races by date range and track, newest first
export const filterAndSortRaces = (races: Array<IRace>, filter: IRacesFilter): Array<IRace> => {
	const endBound = filter.endDate > 0 ? filter.endDate : Infinity;
	return races
		.filter(r => r.startDate >= filter.startDate && r.endDate <= endBound)
		.filter(r => !filter.trackId || filter.trackId === 'All' || String(r.trackName) === String(filter.trackId))
		.sort((a, b) => b.startDate - a.startDate);
};
