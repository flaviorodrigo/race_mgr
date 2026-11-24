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

export interface INextSessionSettings {
	TrackId?: number,
	RaceLength?: number,
	PracticeLength?: number,
	QualifyLength?: number,
	OpponentDifficulty?: number,
}

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
export const saveServerConfig = (changes: ServerConfigValues) => call({url: '/gameserver/config', method: 'put', data: changes});

export interface IGameListItem {
	id: string | number,
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
