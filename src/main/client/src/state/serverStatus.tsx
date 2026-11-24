import {createSlice} from '@reduxjs/toolkit';

export interface IServerStatusState {
    isRunning: boolean,
	managed: boolean,
	// Race Manager can restart it (it runs it, or the game server's container has the restart signal set up)
	restartable: boolean,
	isLoading: boolean,
	hasError: boolean
  }

const initialState: IServerStatusState = {
	isRunning: true,
	managed: true,
	restartable: true,
	isLoading: true,
	hasError: false,
};

export const serverStatusSlice = createSlice({
	name: 'serverStatus',
	initialState,
	reducers: {
		getServerStatus(state) {
			state.isLoading = true;
		},
		getServerStatusSuccess(state, action) {
			state.isRunning = action.payload.isRunning;
			// False when the game server is run by something else (e.g. its own container)
			state.managed = action.payload.managed !== false;
			state.restartable = action.payload.restartable !== false;
			state.isLoading = false;
		},
		getServerStatusFailure(state) {
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
