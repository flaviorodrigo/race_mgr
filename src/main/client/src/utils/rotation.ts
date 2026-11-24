// The rotation builder's model of a rotation file (the game server's sms_rotate format). Everything the builder
// doesn't edit is kept as it was, so loading a rotation and saving it again changes nothing.
export const CLEAR = 'Clear';

type Weather = string | number; // A weather name ("Clear") or the game's numeric hash

export interface IRotationEntry {
	track: string,
	qualifyWeather1: Weather,
	qualifyWeather2: Weather,
	raceWeather1: Weather,
	raceWeather2: Weather,
	// The round's settings as loaded (its weather slots included), kept untouched
	extra: Record<string, unknown>,
}

export interface IRotationModel {
	persistIndex: boolean,
	defaults: Record<string, unknown>,
	entries: Array<IRotationEntry>,
}

const WEATHER: Array<[keyof IRotationEntry, string]> = [
	['qualifyWeather1', 'QualifyWeatherSlot1'],
	['qualifyWeather2', 'QualifyWeatherSlot2'],
	['raceWeather1', 'RaceWeatherSlot1'],
	['raceWeather2', 'RaceWeatherSlot2'],
];

export const emptyEntry = (): IRotationEntry => ({track: '', qualifyWeather1: CLEAR, qualifyWeather2: CLEAR, raceWeather1: CLEAR, raceWeather2: CLEAR, extra: {}});

const inheritedWeather = (defaults: Record<string, unknown>, key: string): Weather => (defaults[key] as Weather | undefined) ?? CLEAR;

// Reads `config` (with `default` or `defaultConfig`); null when it isn't a rotation the builder can show
export const modelFromConfig = (config: any): IRotationModel | null => {
	const defaults = config?.defaultConfig ?? config?.default;
	const rotation = config?.rotation;
	if (!defaults || typeof defaults !== 'object' || !Array.isArray(rotation) || rotation.length === 0) {
		return null;
	}

	if (rotation.some((r: any) => typeof r?.TrackId !== 'string' || r.TrackId === '')) {
		return null;
	}

	return {
		persistIndex: config.persist_index !== false,
		defaults: {...defaults},
		entries: rotation.map((r: any) => {
			const {TrackId: track, ...extra} = r;
			const entry: any = {track, extra};
			for (const [field, key] of WEATHER) {
				entry[field] = (r[key] as Weather | undefined) ?? inheritedWeather(defaults, key);
			}

			return entry as IRotationEntry;
		}),
	};
};

// The rotation as Race Manager stores it. A round only carries a weather slot that it had already, or that now
// differs from the default setup (where it would otherwise come from anyway).
/* eslint-disable camelcase */
export const configFromModel = (model: IRotationModel) => ({
	persist_index: model.persistIndex,
	defaultConfig: model.defaults,
	rotation: model.entries.map(e => {
		const round: Record<string, unknown> = {TrackId: e.track, ...e.extra};
		for (const [field, key] of WEATHER) {
			const value = e[field] as Weather;
			if (key in e.extra || value !== inheritedWeather(model.defaults, key)) {
				round[key] = value;
			}
		}

		return round;
	}),
});
/* eslint-enable camelcase */
