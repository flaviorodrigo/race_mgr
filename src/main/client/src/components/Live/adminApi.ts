import RaceMgrApi from '../../api/RaceMgrApi';

// Small helpers for the admin tools. Each resolves to {ok, message, data}; the message is the server's own
// explanation (already safe to show) or a generic one.
export interface IApiResult<T = unknown> {
	ok: boolean,
	message: string,
	data?: T,
}

const call = async <T>(config: {url: string, method: 'get' | 'post' | 'delete', data?: unknown}): Promise<IApiResult<T>> => {
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

export interface IBanEntry {
	steamId: string,
	note: string,
}

export const fetchBanList = () => call<Array<IBanEntry>>({url: '/gameserver/banlist', method: 'get'});
export const addBan = (steamId: string, note: string) => call({url: '/gameserver/banlist', method: 'post', data: {steamId, note}});
export const removeBan = (steamId: string) => call({url: `/gameserver/banlist/${steamId}`, method: 'delete'});

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
