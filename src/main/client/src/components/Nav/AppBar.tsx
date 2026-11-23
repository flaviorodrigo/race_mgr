import React from 'react';
import {styled, createTheme, ThemeProvider} from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';
import MuiDrawer from '@mui/material/Drawer';
import Box from '@mui/material/Box';
import MuiAppBar, {AppBarProps as MuiAppBarProps} from '@mui/material/AppBar';
import Toolbar from '@mui/material/Toolbar';
import RaceMgrApi from '../../api/RaceMgrApi';
import List from '@mui/material/List';
import Typography from '@mui/material/Typography';
import Divider from '@mui/material/Divider';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import Container from '@mui/material/Container';

import LeaderBoard from '../Leaderboard/LeaderBoard';
import ManageServer from '../ManageServer/ManageServer';
import Login from '../Login/Login';

import ListItemButton from '@mui/material/ListItemButton';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Footer from './Footer';

import {
	BrowserRouter as Router,
	Routes, Route, Link as RouterLink,
} from 'react-router-dom';

import MenuIcon from '@mui/icons-material/Menu';
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft';
import BarChartIcon from '@mui/icons-material/BarChart';
import PermDataSettingIcon from '@mui/icons-material/PermDataSetting';
import LoginIcon from '@mui/icons-material/Login';
import LogoutIcon from '@mui/icons-material/Logout';
import ServerStopped from '../ErrorStates/ServerStopped';
import ServerConfigError from '../ErrorStates/ServerConfigError';
import ServerStatusError from '../ErrorStates/ServerStatusError';

const drawerWidth: number = 240;

interface AppBarProps extends MuiAppBarProps {
  open?: boolean;
}

const AppBar = styled(MuiAppBar, {
	shouldForwardProp: prop => prop !== 'open',
})<AppBarProps>(({theme, open}) => ({
	zIndex: theme.zIndex.drawer + 1,
	transition: theme.transitions.create(['width', 'margin'], {
		easing: theme.transitions.easing.sharp,
		duration: theme.transitions.duration.leavingScreen,
	}),
	...(open && {
		marginLeft: drawerWidth,
		width: `calc(100% - ${drawerWidth}px)`,
		transition: theme.transitions.create(['width', 'margin'], {
			easing: theme.transitions.easing.sharp,
			duration: theme.transitions.duration.enteringScreen,
		}),
	}),
}));

const Drawer = styled(MuiDrawer, {shouldForwardProp: prop => prop !== 'open'})(
	({theme, open}) => ({
		'& .MuiDrawer-paper': {
			position: 'relative',
			whiteSpace: 'nowrap',
			width: drawerWidth,
			transition: theme.transitions.create('width', {
				easing: theme.transitions.easing.sharp,
				duration: theme.transitions.duration.enteringScreen,
			}),
			boxSizing: 'border-box',
			...(!open && {
				overflowX: 'hidden',
				transition: theme.transitions.create('width', {
					easing: theme.transitions.easing.sharp,
					duration: theme.transitions.duration.leavingScreen,
				}),
				width: theme.spacing(7),
				[theme.breakpoints.up('sm')]: {
					width: theme.spacing(9),
				},
			}),
		},
	}),
);

const mdTheme = createTheme({
	palette: {
		primary: {
			main: '#ff2800',
		},
		secondary: {
			main: '#636262',
		},
	},
});

// Router link that looks like plain text in the nav
const NavLink = styled(RouterLink)(({theme}) => ({
	textDecoration: 'none',
	color: theme.palette.text.primary,
}));

const DashboardContent = () => {
	const [open, setOpen] = React.useState(true);
	// Start from the session marker so a reload on an admin page doesn't bounce to the login page first
	const [isAuthenticated, setIsAuthenticated] = React.useState(() => new RaceMgrApi().hasSession());

	// Set the defaults
	const toggleDrawer = () => {
		setOpen(!open);
	};

	const handleLogout = async () => {
		await new RaceMgrApi().doLogout();
		setIsAuthenticated(false);
	};

	return (
		<ThemeProvider theme={mdTheme}>
			<Router>
				<Box sx={{display: 'flex'}}>
					<CssBaseline />
					<AppBar position="absolute" open={open}>
						<Toolbar
							sx={{
								pr: '24px', // Keep right padding when drawer closed
							}}
						>
							<IconButton
								edge="start"
								color="inherit"
								aria-label="open drawer"
								onClick={toggleDrawer}
								sx={{
									marginRight: '36px',
									...(open && {display: 'none'}),
								}}
							>
								<MenuIcon />
							</IconButton>
							<Typography
								component="h1"
								variant="h6"
								color="inherit"
								noWrap
								sx={{flexGrow: 1}}
							>
								<div style={{
									marginRight: '8px',
									top: '-8px',
									position: 'relative',
									display: 'inline',
								}}>Race Manager</div>
								<img src="race_mgr_logo.png" alt="logo" width="50px" />
							</Typography>

							{!isAuthenticated
							&& 	<NavLink to="/login">
								<IconButton sx={{color: 'white'}} aria-label="Login">
									<Tooltip title="Login">
										<LoginIcon/>
									</Tooltip>
								</IconButton>
							</NavLink>
							}

							{isAuthenticated
							&& <IconButton sx={{color: 'white'}} aria-label="Logout" onClick={handleLogout}>
								<Tooltip title="Logout">
									<LogoutIcon/>
								</Tooltip>
							</IconButton>
							}
						</Toolbar>
					</AppBar>

					<Drawer variant="permanent" open={open}>
						<Toolbar
							sx={{
								display: 'flex',
								alignItems: 'center',
								justifyContent: 'flex-end',
								px: [1],
							}}
						>
							<IconButton onClick={toggleDrawer}>
								<ChevronLeftIcon />
							</IconButton>
						</Toolbar>
						<Divider />
						<List component="nav">
							<NavLink to="/">
								<ListItemButton>
									<ListItemIcon>
										<BarChartIcon />
									</ListItemIcon>
									<ListItemText primary="Leader Board" />
								</ListItemButton>
							</NavLink>

							{isAuthenticated
							&&							<NavLink to="/manageserver">
								<ListItemButton>
									<ListItemIcon>
										<PermDataSettingIcon />
									</ListItemIcon>
									<ListItemText primary="Mange Server" />
								</ListItemButton>
							</NavLink>
							}

						</List>
					</Drawer>

					<Box
						component="main"
						sx={{
							backgroundColor: theme =>
								theme.palette.mode === 'light'
									? theme.palette.grey[100]
									: theme.palette.grey[900],
							flexGrow: 1,
							height: '100vh',
							overflow: 'auto',
						}}
					>
						<Toolbar />
						<Container maxWidth="lg" sx={{mt: 4, mb: 4}}>
							<Routes>
								<Route path="/" element={<LeaderBoard />} />
								<Route path="/manageServer" element={<ManageServer isAuthenticated={isAuthenticated} />} />
								<Route path="/login" element={<Login isAuthenticated={isAuthenticated} setIsAuthenticated={setIsAuthenticated} />} />
								<Route path="/serverstopped" element={<ServerStopped />} />
								<Route path="/serverconfigerror" element={<ServerConfigError />} />
								<Route path="/serverstatuserror" element={<ServerStatusError />} />
							</Routes>
							<Footer />
						</Container>
					</Box>
				</Box>
			</Router>

		</ThemeProvider>
	);
};

export default function Dashboard() {
	return <DashboardContent />;
}
