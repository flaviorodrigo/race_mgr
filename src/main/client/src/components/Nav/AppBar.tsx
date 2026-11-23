import React from 'react';
import {styled, useTheme, ThemeProvider} from '@mui/material/styles';
import useMediaQuery from '@mui/material/useMediaQuery';
import Stack from '@mui/material/Stack';
import CssBaseline from '@mui/material/CssBaseline';
import MuiDrawer from '@mui/material/Drawer';
import Box from '@mui/material/Box';
import MuiAppBar, {AppBarProps as MuiAppBarProps} from '@mui/material/AppBar';
import Toolbar from '@mui/material/Toolbar';
import RaceMgrApi from '../../api/RaceMgrApi';
import List from '@mui/material/List';
import Typography from '@mui/material/Typography';
import theme from '../../theme';
import ColorModeToggle from './ColorModeToggle';
import Divider from '@mui/material/Divider';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import Container from '@mui/material/Container';

import LeaderBoard from '../Leaderboard/LeaderBoard';
import RacePage from '../Leaderboard/RacePage';
import DriverPage from '../Leaderboard/DriverPage';
import LiveTiming from '../Live/LiveTiming';
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
import TimerIcon from '@mui/icons-material/Timer';
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

// On wide screens the app bar shifts right when the (mini) drawer is expanded
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

// Router link that looks like plain text in the nav
const NavLink = styled(RouterLink)({
	textDecoration: 'none',
	color: 'inherit',
});

interface INavItemsProps {
	isAuthenticated: boolean,
	onNavigate?: () => void,
}

const NavItems = ({isAuthenticated, onNavigate}: INavItemsProps) => (
	<List component="nav">
		<NavLink to="/" onClick={onNavigate}>
			<ListItemButton>
				<ListItemIcon>
					<BarChartIcon />
				</ListItemIcon>
				<ListItemText primary="Leader Board" />
			</ListItemButton>
		</NavLink>

		<NavLink to="/live" onClick={onNavigate}>
			<ListItemButton>
				<ListItemIcon>
					<TimerIcon />
				</ListItemIcon>
				<ListItemText primary="Live Timing" />
			</ListItemButton>
		</NavLink>

		{isAuthenticated
		&& <NavLink to="/manageserver" onClick={onNavigate}>
			<ListItemButton>
				<ListItemIcon>
					<PermDataSettingIcon />
				</ListItemIcon>
				<ListItemText primary="Manage Server" />
			</ListItemButton>
		</NavLink>
		}
	</List>
);

const DashboardContent = () => {
	const muiTheme = useTheme();
	// Phones and small tablets get an overlay drawer instead of a permanent one
	const isCompact = useMediaQuery(muiTheme.breakpoints.down('md'));

	const [open, setOpen] = React.useState(true);
	const [mobileOpen, setMobileOpen] = React.useState(false);
	// Start from the session marker so a reload on an admin page doesn't bounce to the login page first
	const [isAuthenticated, setIsAuthenticated] = React.useState(() => new RaceMgrApi().hasSession());

	const toggleDrawer = () => {
		if (isCompact) {
			setMobileOpen(!mobileOpen);
		} else {
			setOpen(!open);
		}
	};

	const handleLogout = async () => {
		await new RaceMgrApi().doLogout();
		setIsAuthenticated(false);
	};

	const showMenuButton = isCompact || !open;

	return (
		<Router>
			<Box sx={{display: 'flex'}}>
				<CssBaseline enableColorScheme />
				<AppBar position="absolute" open={open && !isCompact} color="primary" enableColorOnDark>
					<Toolbar sx={{pr: {xs: 1, sm: '24px'}}}>
						<IconButton
							edge="start"
							color="inherit"
							aria-label="open menu"
							onClick={toggleDrawer}
							sx={{
								marginRight: {xs: 1, sm: '36px'},
								...(!showMenuButton && {display: 'none'}),
							}}
						>
							<MenuIcon />
						</IconButton>
						<Stack direction="row" spacing={1.5} sx={{flexGrow: 1, minWidth: 0, alignItems: 'center', justifyContent: 'center'}}>
							<Typography component="h1" variant="h6" color="inherit" noWrap>
								Race Manager
							</Typography>
							<Box component="img" src="/race_mgr_logo.png" alt="" sx={{height: 36, display: {xs: 'none', sm: 'block'}}} />
						</Stack>

						<ColorModeToggle />

						{!isAuthenticated
						&& <Tooltip title="Login">
							<IconButton color="inherit" aria-label="Login" component={RouterLink} to="/login">
								<LoginIcon />
							</IconButton>
						</Tooltip>
						}

						{isAuthenticated
						&& <Tooltip title="Logout">
							<IconButton color="inherit" aria-label="Logout" onClick={handleLogout}>
								<LogoutIcon />
							</IconButton>
						</Tooltip>
						}
					</Toolbar>
				</AppBar>

				{isCompact
					? <MuiDrawer
						variant="temporary"
						open={mobileOpen}
						onClose={() => setMobileOpen(false)}
						ModalProps={{keepMounted: true}}
						sx={{'& .MuiDrawer-paper': {width: drawerWidth, boxSizing: 'border-box'}}}
					>
						<Toolbar sx={{display: 'flex', alignItems: 'center', justifyContent: 'flex-end', px: [1]}}>
							<IconButton onClick={() => setMobileOpen(false)} aria-label="close menu">
								<ChevronLeftIcon />
							</IconButton>
						</Toolbar>
						<Divider />
						<NavItems isAuthenticated={isAuthenticated} onNavigate={() => setMobileOpen(false)} />
					</MuiDrawer>
					: <Drawer variant="permanent" open={open}>
						<Toolbar sx={{display: 'flex', alignItems: 'center', justifyContent: 'flex-end', px: [1]}}>
							<IconButton onClick={toggleDrawer} aria-label="collapse menu">
								<ChevronLeftIcon />
							</IconButton>
						</Toolbar>
						<Divider />
						<NavItems isAuthenticated={isAuthenticated} />
					</Drawer>
				}

				<Box
					component="main"
					sx={{
						bgcolor: 'background.default',
						flexGrow: 1,
						minWidth: 0, // Lets wide tables scroll inside instead of stretching the page
						height: '100vh',
						overflow: 'auto',
					}}
				>
					<Toolbar />
					<Container maxWidth="lg" sx={{mt: {xs: 2, md: 4}, mb: 4, px: {xs: 1.5, sm: 3}}}>
						<Routes>
							<Route path="/" element={<LeaderBoard />} />
							<Route path="/manageServer" element={<ManageServer isAuthenticated={isAuthenticated} />} />
							<Route path="/live" element={<LiveTiming />} />
							<Route path="/driver/:name" element={<DriverPage />} />
							<Route path="/race/:raceId" element={<RacePage />} />
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
	);
};

export default function Dashboard() {
	return (
		<ThemeProvider theme={theme}>
			<DashboardContent />
		</ThemeProvider>
	);
}
