import {createSlice} from '@reduxjs/toolkit';

export type SteamStatus = 'UNKNOWN' | 'CHECKING' | 'REACHABLE' | 'UNLISTED';

export interface ISteamListing {
	name: string,
	addr: string,
	map: string,
	players: number,
	maxPlayers: number,
	gameType: string,
}

export interface IServerStatusState {
    isRunning: boolean,
	managed: boolean,
	// Race Manager can restart it (it runs it, or the game server's container has the restart signal set up)
	restartable: boolean,
	steamStatus: SteamStatus,
	steamListing: ISteamListing | null,
	steamCheckedAt: number | null,
	appVersion: string | null,
	hasLoaded: boolean,
	isLoading: boolean,
	hasError: boolean
  }

const initialState: IServerStatusState = {
	isRunning: true,
	managed: true,
	restartable: true,
	steamStatus: 'UNKNOWN',
	steamListing: null,
	steamCheckedAt: null,
	appVersion: null,
	hasLoaded: false,
	isLoading: true,
	hasError: false,
};

export const serverStatusSlice = createSlice({
	name: 'serverStatus',
	initialState,
	reducers: {
		getServerStatus(state) {
			if (!state.hasLoaded) {
				state.isLoading = true;
			}
		},
		getServerStatusSuccess(state, action) {
			state.isRunning = action.payload.isRunning;
			// False when the game server is run by something else (e.g. its own container)
			state.managed = action.payload.managed !== false;
			state.restartable = action.payload.restartable !== false;
			state.steamStatus = action.payload.steamStatus ?? 'UNKNOWN';
			state.steamListing = action.payload.steamListing ?? null;
			state.steamCheckedAt = action.payload.steamCheckedAt ?? null;
			state.appVersion = action.payload.appVersion ?? state.appVersion;
			state.hasLoaded = true;
			state.isLoading = false;
			state.hasError = false;
		},
		getServerStatusFailure(state) {
			state.hasLoaded = true;
			state.hasError = true;
			state.isLoading = false;
		},
		updateServerStatus(state, action) {
			state.isLoading = true;
		},
		updateServerStatusSuccess(state) {
			state.hasError = false;
		},
		updateServerStatusFailure(state) {
			state.hasError = true;
			state.isLoading = false;
		},
	},
});

export const {getServerStatus, getServerStatusSuccess, getServerStatusFailure, updateServerStatus, updateServerStatusSuccess, updateServerStatusFailure} = serverStatusSlice.actions;
export default serverStatusSlice.reducer;
