import React from 'react';
import {createRoot} from 'react-dom/client';
import './index.css';
import App from './App';
import {Provider} from 'react-redux';
import createSagaMiddleWare from 'redux-saga';
import {configureStore, Tuple} from '@reduxjs/toolkit';
import rootSaga from './sagas/rootSaga';

import serverConfigReducer from './state/serverConfig';
import serverStatusReducer from './state/serverStatus';
import sessionsReducer from './state/sessions';
import rotationsReducer from './state/rotations';
import rotationDetailReducer from './state/rotationDetail';

const saga = createSagaMiddleWare();

const store = configureStore({
	reducer: {
		serverConfig: serverConfigReducer,
		serverStatus: serverStatusReducer,
		sessions: sessionsReducer,
		rotations: rotationsReducer,
		rotationDetail: rotationDetailReducer,
	},
	middleware: () => new Tuple(saga),
});

saga.run(rootSaga);

createRoot(document.getElementById('root') as HTMLElement).render(
	<Provider store={store}>
		<App />
	</Provider>,
);
