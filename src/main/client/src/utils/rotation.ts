// A rotation as the builder edits it: the default setup and the rounds, each a plain map of session settings, so
// loading a rotation and saving it again changes nothing.
export interface IRotationModel {
	persistIndex: boolean,
	defaults: Record<string, unknown>,
	rounds: Array<Record<string, unknown>>,
}

const hasTrack = (round: any) => (typeof round?.TrackId === 'string' && round.TrackId !== '') || typeof round?.TrackId === 'number';

// Reads `config` (with `default` or `defaultConfig`); null when it isn't a rotation the builder can show
export const modelFromConfig = (config: any): IRotationModel | null => {
	const defaults = config?.defaultConfig ?? config?.default;
	const rotation = config?.rotation;
	if (!defaults || typeof defaults !== 'object' || Array.isArray(defaults) || !Array.isArray(rotation) || rotation.length === 0) {
		return null;
	}

	if (!rotation.every(r => r && typeof r === 'object' && hasTrack(r))) {
		return null;
	}

	return {persistIndex: config.persist_index !== false, defaults: {...defaults}, rounds: rotation.map((r: any) => ({...r}))};
};

/* eslint-disable camelcase */
export const configFromModel = (model: IRotationModel) => ({
	persist_index: model.persistIndex,
	defaultConfig: model.defaults,
	rotation: model.rounds,
});
/* eslint-enable camelcase */
