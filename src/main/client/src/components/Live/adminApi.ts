import RaceMgrApi from '../../api/RaceMgrApi';
import {IPenalty} from '../../utils/penalties';
import {Role} from '../../utils/roles';
import {ISchema} from '../../utils/sessionSettings';

// Small helpers for the admin tools. Each resolves to {ok, message, data}; the message is the server's own
// explanation (already safe to show) or a generic one.
export interface IApiResult<T = unknown> {
	ok: boolean,
	message: string,
	data?: T,
}

const call = async <T>(config: {url: string, method: 'get' | 'post' | 'put' | 'delete', data?: unknown}): Promise<IApiResult<T>> => {
	const rmApi = new RaceMgrApi();
	try {
		const response = await rmApi.makeApiCall({...config, url: `${rmApi.getBaseAdminApiUrl()}${config.url}`});
		const body = response.data;
		return {ok: body?.status === 200, message: body?.message ?? '', data: body?.data};
	} catch (error: any) {
		return {ok: false, message: error?.response?.data?.message ?? 'Request failed'};
	}
};

// Any writable session setting, as the whole number the game server's API takes
export type INextSessionSettings = Record<string, number>;

export const applyNextSession = (settings: INextSessionSettings) => call({url: '/gameserver/session/next', method: 'post', data: settings});
export const advanceSession = () => call({url: '/gameserver/session/advance', method: 'post'});

export interface ISessionPreset {
	id: string,
	name: string,
	settings: INextSessionSettings,
}

export const fetchPresets = () => call<Array<ISessionPreset>>({url: '/gameserver/presets', method: 'get'});
export const savePreset = (name: string, settings: INextSessionSettings) => call({url: '/gameserver/presets', method: 'post', data: {name, settings}});
export const deletePreset = (id: string) => call({url: `/gameserver/presets/${id}`, method: 'delete'});
export const applyPreset = (id: string) => call({url: `/gameserver/presets/${id}/apply`, method: 'post'});

export interface IBanEntry {
	steamId: string,
	note: string,
}

export const fetchBanList = () => call<Array<IBanEntry>>({url: '/gameserver/banlist', method: 'get'});
export const addBan = (steamId: string, note: string) => call({url: '/gameserver/banlist', method: 'post', data: {steamId, note}});
export const removeBan = (steamId: string) => call({url: `/gameserver/banlist/${steamId}`, method: 'delete'});

export interface IWhitelist {
	entries: Array<IBanEntry>,
	// Whether server.cfg points its whiteList at whitelist.cfg
	active: boolean,
}

export const fetchWhitelist = () => call<IWhitelist>({url: '/gameserver/whitelist', method: 'get'});
export const addToWhitelist = (steamId: string, note: string) => call({url: '/gameserver/whitelist', method: 'post', data: {steamId, note}});
export const setWhitelistActive = (enabled: boolean) => call({url: '/gameserver/whitelist/active', method: 'put', data: {enabled}});
export const removeFromWhitelist = (steamId: string) => call({url: `/gameserver/whitelist/${steamId}`, method: 'delete'});

export interface ILogEvent {
	index?: number,
	time?: number,
	name: string,
	refid?: number,
	participantid?: number,
	attributes: Record<string, string>,
}

export const fetchGameLog = (count: number) => call<Array<ILogEvent>>({url: `/gameserver/log?count=${count}`, method: 'get'});

export const saveRaceVideo = (raceId: string, url: string) => call({url: `/race/events/${raceId}/video`, method: 'put', data: {url}});
export const saveRacePenalties = (raceId: string, penalties: Array<IPenalty>) =>
	call({url: `/race/events/${raceId}/penalties`, method: 'put', data: {penalties}});

export interface IAdminUser {
	username: string,
	role: Role,
	created?: string,
}

export const fetchMe = () => call<IAdminUser>({url: '/me', method: 'get'});
export const fetchAdminUsers = () => call<Array<IAdminUser>>({url: '/users', method: 'get'});
export const createAdminUser = (username: string, password: string, role: Role) => call({url: '/users', method: 'post', data: {username, password, role}});
export const updateAdminUser = (username: string, change: {role?: Role, password?: string}) => call({url: `/users/${username}`, method: 'put', data: change});
export const deleteAdminUser = (username: string) => call({url: `/users/${username}`, method: 'delete'});

export interface IAuditEvent {
	id: string,
	time: string,
	actor: string,
	action: string,
	detail: string,
}

export const fetchAudit = () => call<Array<IAuditEvent>>({url: '/audit?limit=100', method: 'get'});

export const changePassword = (currentPassword: string, newPassword: string) =>
	call({url: '/password', method: 'post', data: {currentPassword, newPassword}});

export type ServerConfigValues = Record<string, string | number | boolean>;

export const fetchServerConfig = () => call<ServerConfigValues>({url: '/gameserver/config', method: 'get'});
// The settings every session starts from (server.cfg's sessionAttributes block): whole numbers by setting name
export const fetchSessionDefaults = () => call<Record<string, number>>({url: '/gameserver/config/session', method: 'get'});
export const saveSessionDefaults = (changes: INextSessionSettings) => call({url: '/gameserver/config/session', method: 'put', data: changes});
export const saveServerConfig = (changes: ServerConfigValues) => call({url: '/gameserver/config', method: 'put', data: changes});

export interface IGameListItem {
	// Tracks and cars have an id; car classes a value
	id?: string | number,
	value?: number,
	name: string,
	translated_name?: string,
}

// The game server's own lists (tracks, vehicle classes, weather...) through Race Manager
export const fetchGameList = async (kind: 'tracks' | 'vehicle_classes' | 'vehicles' | 'weather'): Promise<Array<IGameListItem>> => {
	const rmApi = new RaceMgrApi();
	try {
		const response = await rmApi.makeApiCall({url: `${rmApi.getBaseApiUrl()}/gameserver/${kind}`, method: 'get'});
		const list = response.data?.data;
		return Array.isArray(list) ? list : [];
	} catch {
		return [];
	}
};

export interface ISavedRotation {
	id: string,
	name: string,
}

export const fetchSavedRotations = async (): Promise<Array<ISavedRotation>> => {
	const rmApi = new RaceMgrApi();
	try {
		const response = await rmApi.makeApiCall({url: `${rmApi.getBaseApiUrl()}/race/rotation?page=0&limit=1000`, method: 'get'});
		return response.data?.data?.content ?? [];
	} catch {
		return [];
	}
};

export const fetchRotationConfig = async (id: string): Promise<any> => {
	const rmApi = new RaceMgrApi();
	try {
		const response = await rmApi.makeApiCall({url: `${rmApi.getBaseApiUrl()}/race/rotation/${id}`, method: 'get'});
		return response.data?.data?.config ?? null;
	} catch {
		return null;
	}
};

// Saves a custom rotation without applying it: activate it afterwards with Update Server
export const saveCustomRotation = (name: string, config: unknown) => call({url: '/race/rotation', method: 'post', data: {name, config, apply: false}});
// Changes a saved rotation in place (its races stay under it)
export const updateCustomRotation = (id: string, name: string, config: unknown) => call({url: `/race/rotation/${id}`, method: 'put', data: {name, config}});
export const deleteRotation = (id: string) => call({url: `/race/rotation/${id}`, method: 'delete'});

export interface IPerformanceTable {
	enabled: boolean,
	defaultValue: number,
	drivers: Record<string, number>,
	file?: string,
}

export interface IPerformance {
	ballast: IPerformanceTable,
	power: IPerformanceTable,
	drag: IPerformanceTable,
}

export const fetchPerformance = () => call<IPerformance>({url: '/gameserver/performance', method: 'get'});
export const savePerformance = (tables: IPerformance) => call({url: '/gameserver/performance', method: 'put', data: tables});

// Every session setting, as the game server describes it (for the rotation builder)
export const fetchSessionSchema = () => call<ISchema>({url: '/gameserver/schema', method: 'get'});

// Restarts a game server that runs in its own container (through the restart signal); resetRotation starts its
// rotation again from round 1
export const restartGameServer = (resetRotation: boolean) => call({url: '/gameserver/restart', method: 'post', data: {resetRotation}});
export const fetchRestartStatus = () => call<{enabled: boolean, pending: boolean}>({url: '/gameserver/restart', method: 'get'});

// The live lobby (or race) next to what the rotation on the game server says it should have
export interface ILobbyRow {
	name: string,
	expected: string | null,
	live: string | null,
	match: boolean | null,
}
export interface ILobbyCheck {
	state: string | null,
	sessionOpen: boolean,
	round: number | null,
	rounds: number,
	liveTrack: string | null,
	note: string | null,
	rows: Array<ILobbyRow>,
}
export const fetchLobbyCheck = () => call<ILobbyCheck>({url: '/gameserver/lobby', method: 'get'});

// The racemgr_rotate addon, which lets a rotation change without restarting the game server
export interface IRotationAddon {
	installed: boolean,
	listed: boolean,
	smsRotateListed: boolean,
	conflict: boolean,
	ready: boolean,
	reported: null | {version?: number, index?: number, rounds?: number, reset?: number, ok: boolean, note: string},
}
export const fetchRotationAddon = () => call<IRotationAddon>({url: '/gameserver/rotation-addon', method: 'get'});
export const startRotationOver = () => call<{version: number}>({url: '/gameserver/rotation-addon/start-over', method: 'post'});
export const installRotationAddon = () => call<IRotationAddon>({url: '/gameserver/rotation-addon/install', method: 'post'});
