import React, {useState, useEffect} from 'react';
import {useSelector, useDispatch} from 'react-redux';
import {getServerConfig} from '../../state/serverConfig';
import {getServerStatus} from '../../state/serverStatus';
import {setSelectedSessionId} from '../../state/sessions';
import {setSelectedRotationId} from '../../state/rotations';

import ServerConfig from './ServerConfig';

import {Navigate, useNavigate, useSearchParams} from 'react-router-dom';

import Typography from '@mui/material/Typography';
import Snackbar from '@mui/material/Snackbar';
import Alert from '@mui/material/Alert';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';

import ApplyServerConfig from './ApplyServerConfig';
import LivePanel from '../Live/LivePanel';
import AdminTools, {AdminTab} from './AdminTools';
import GameServerRestart from './GameServerRestart';
import {fetchRotationAddon, IRotationAddon} from '../Live/adminApi';
import Tab from '@mui/material/Tab';
import Tabs from '@mui/material/Tabs';
import {atLeast} from '../../utils/roles';
import {useRole} from '../Live/useRole';

import Paper from '@mui/material/Paper';

import Backdrop from '@mui/material/Backdrop';
import CircularProgress from '@mui/material/CircularProgress';
import {sanitizeString} from '../../utils/stringUtils';

import ReportProblemIcon from '@mui/icons-material/ReportProblem';

type notificationSeverity = 'success' | 'error';

interface IManageServerProps {
	isAuthenticated: boolean,
}

const ManageServer = (props:IManageServerProps) => {
	const {serverConfig, serverStatus, sessions, rotations, rotationDetail} = useSelector((state:any) => state);

	const [notificationIsOpen, setNotificationIsOpen] = useState(false);
	const [notificationMessage, setNotificationMessage] = useState('');
	const [notificationSeverity, setNotificationSeverity] = useState<notificationSeverity>('success');

	const dispatch = useDispatch();
	const navigate = useNavigate();
	// Stewards run race day: the series and rotation controls are for admins
	const {role, loaded: roleLoaded} = useRole();
	const isAdmin = atLeast(role, 'ADMIN');
	// Stewards get race day and their account; admins everything. The tab is kept in the address (?tab=server).
	const tabs: Array<{id: AdminTab, label: string}> = isAdmin
		? [{id: 'race-day', label: 'Race day'}, {id: 'championships', label: 'Championships'}, {id: 'server', label: 'Server'}, {id: 'people', label: 'People'}]
		: [{id: 'race-day', label: 'Race day'}, {id: 'people', label: 'People'}];
	const [search, setSearch] = useSearchParams();
	const wanted = search.get('tab') as AdminTab | null;
	const currentTab: AdminTab = tabs.some(t => t.id === wanted) ? wanted! : 'race-day';
	const setTab = (tab: AdminTab) => setSearch(tab === 'race-day' ? {} : {tab}, {replace: true});

	// Whether the racemgr_rotate addon is in use: then Update Server needs no restart
	const [rotationAddon, setRotationAddon] = useState<IRotationAddon | null>(null);
	useEffect(() => {
		if (currentTab === 'championships') {
			fetchRotationAddon().then(r => setRotationAddon(r.ok && r.data ? r.data : null));
		}
	}, [currentTab]);
	const addonInUse = Boolean(rotationAddon?.ready && rotationAddon.reported);

	useEffect(() => {
		dispatch(getServerStatus());
		dispatch(getServerConfig());
	}, [dispatch]);

	useEffect(() => {
		dispatch(setSelectedSessionId(serverConfig.activeRaceSessionId));
		dispatch(setSelectedRotationId(serverConfig.activeRaceRotationId));
	}, []);

	useEffect(() => {
		if (!sessions.selectedSessionId || sessions.selectedSessionId === 'All') {
			dispatch(setSelectedSessionId(serverConfig.activeRaceSessionId));
		}

		if (!rotations.selectedRotationId || rotations.selectedRotationId === 'All') {
			dispatch(setSelectedRotationId(serverConfig.activeRaceRotationId));
		}
	}, [serverConfig]);

	useEffect(() => {
		if (serverConfig.hasError === true) {
			// History.push('/serverconfigError');
		}
	}, [serverConfig.hasError]);

	useEffect(() => {
		if (serverStatus.hasError === true) {
			navigate('/serverstatusError');
		}
	}, [serverStatus.hasError]);

	const handleClose = (event: React.SyntheticEvent | Event, reason?: string) => {
		if (reason === 'clickaway') {
			return;
		}

		setNotificationIsOpen(false);
	};

	const showNotification = (message:string, severity: notificationSeverity) => {
		setNotificationMessage(message);
		setNotificationSeverity(severity);
		setNotificationIsOpen(true);
	};

	return (
		props.isAuthenticated === false ? <Navigate to="/login" replace />

			:		<Paper sx={{
				p: 2,
				pb: 5,
				display: 'flex',
				flexDirection: 'column',
			}}>
				<Typography variant="h5" align="left" gutterBottom={true}>
				Manage Server   <IconButton sx={{color: 'error.main', visibility: serverStatus.isRunning ? 'hidden' : 'visible'}}>

						<Tooltip title="Server is not Running">
							<ReportProblemIcon />
						</Tooltip>
					</IconButton>
				</Typography>

				<Tabs value={currentTab} onChange={(_e, value) => setTab(value)} variant="scrollable" allowScrollButtonsMobile
					sx={{borderBottom: 1, borderColor: 'divider', mb: 2}} aria-label="Manage Server">
					{tabs.map(t => <Tab key={t.id} value={t.id} label={t.label} />)}
				</Tabs>

				{currentTab === 'race-day' && <>
					{roleLoaded && !isAdmin && <Typography variant="subtitle1" align="left" gutterBottom={true}>
						You are signed in as a steward: you can see who is online, kick and message players, set up the next session and give penalties.
					</Typography>}
					{isAdmin && <GameServerRestart />}
					<LivePanel />
				</>}

				{currentTab === 'championships' && isAdmin && <>
					<Typography variant="subtitle1" align="left" gutterBottom={true}>
						A session is a championship; a rotation is the list of rounds (tracks, cars, weather, rules). Races are recorded under the
						active session and rotation.
					</Typography>

					<Typography variant="subtitle1" align="left" gutterBottom={true}>
						<ServerConfig />
					</Typography>

					<Typography variant="subtitle1" align="left" gutterBottom={true}>
						<ApplyServerConfig
							showNotification={showNotification}
							setIsServerRunning={serverStatus.isRunning}
						></ApplyServerConfig>
					</Typography>

					{serverStatus.managed === false
					&& <Alert severity="info" sx={{textAlign: 'left', mt: 2}}>
						{addonInUse
							? 'The racemgr_rotate addon is in use: Update Server hands the rotation to the running game server, which picks it up within a few seconds. No restart, and nobody has to leave.'
							: serverStatus.restartable
								? 'The game server runs in its own container. After Update Server, use Restart game server on the Race day tab for the new rotation to take effect (or use the Rotation addon in the Server tab to skip the restart).'
								: 'The game server is run outside Race Manager. After changing the rotation, restart the game server yourself for it to take effect.'}
					</Alert>
					}
				</>}

				<AdminTools tab={currentTab} />

				<Snackbar
					anchorOrigin={{vertical: 'bottom', horizontal: 'right'}}
					open={notificationIsOpen}
					autoHideDuration={3000}
					onClose={handleClose}
				>
					<Alert severity={notificationSeverity}>{notificationMessage}</Alert>
				</Snackbar>

				<Backdrop
					sx={{color: '#fff', zIndex: theme => theme.zIndex.drawer + 1}}
					open={serverConfig.isLoading || serverStatus.isLoading || sessions.isLoading || rotations.isLoading || rotationDetail.isLoading}
				>
					<CircularProgress color="inherit" />
				</Backdrop>

			</Paper>
	);
};

export default ManageServer;
