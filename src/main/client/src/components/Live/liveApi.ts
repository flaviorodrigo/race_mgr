import RaceMgrApi from '../../api/RaceMgrApi';

export interface ILiveMember {
	refid?: number,
	steamid?: string,
	name: string,
	ping?: number,
}

export interface ILiveSession {
	stage?: string,
	trackId?: string,
	players: number,
	maxPlayers?: number,
	members: Array<ILiveMember>,
}

// Resolves to null when the game server can't be reached (the API answers 207 "Request failed").
export const fetchLiveSession = async (admin: boolean): Promise<ILiveSession | null> => {
	const rmApi = new RaceMgrApi();
	try {
		const base = admin ? rmApi.getBaseAdminApiUrl() : rmApi.getBaseApiUrl();
		const response = await rmApi.makeApiCall({url: `${base}/gameserver/session`, method: 'get'});
		return response.data?.data ?? null;
	} catch {
		return null;
	}
};

export const kickPlayer = async (refid: number): Promise<boolean> => {
	const rmApi = new RaceMgrApi();
	try {
		const response = await rmApi.makeApiCall({
			url: `${rmApi.getBaseAdminApiUrl()}/gameserver/kick`,
			method: 'post',
			data: {refid},
		});
		return response.data?.status === 200;
	} catch {
		return false;
	}
};

export const sendChat = async (message: string): Promise<boolean> => {
	const rmApi = new RaceMgrApi();
	try {
		const response = await rmApi.makeApiCall({
			url: `${rmApi.getBaseAdminApiUrl()}/gameserver/chat`,
			method: 'post',
			data: {message},
		});
		return response.data?.status === 200;
	} catch {
		return false;
	}
};

// "Practice1" -> "Practice 1", "PostRace" -> "Post Race"
export const prettyStage = (stage?: string) => (stage ?? '').replace(/([a-z])([A-Z0-9])/g, '$1 $2');
