import React, {useEffect, useRef, useState} from 'react';
import {styled} from '@mui/material/styles';

import {doAuth} from './utilites';
import RaceMgrApi from '../../api/RaceMgrApi';

import {Navigate, useSearchParams} from 'react-router-dom';

import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import Paper from '@mui/material/Paper';
import Backdrop from '@mui/material/Backdrop';
import CircularProgress from '@mui/material/CircularProgress';
import Divider from '@mui/material/Divider';

interface ILoginProps {
	isAuthenticated: boolean,
	setIsAuthenticated: (isAuthenticated:boolean) => void
}

const ErrorTypography = styled(Typography)(({theme}) => ({
	color: theme.palette.error.main,
}));

const Login = (props:ILoginProps) => {
	const [loading, setLoading] = useState(false);
	const [userName, setUserName] = useState('');
	const [password, setPassword] = useState('');
	const [errorMessage, setErrorMessage] = useState('');
	const [ssoEnabled, setSsoEnabled] = useState(false);
	const [searchParams] = useSearchParams();
	const ssoReturn = searchParams.get('sso') === '1';
	const ssoTried = useRef(false);

	// Sign in with the Synology account the sign-in proxy already knows. Its token only lasts minutes, so when it
	// is too old, go through Synology once more (/oauth2/start is the proxy's) and come back here to finish.
	const handleSsoLogin = async (afterFreshSignIn: boolean) => {
		setLoading(true);
		setErrorMessage('');
		const {result, message} = await new RaceMgrApi().doSsoLogin();
		if (result === 'OK') {
			props.setIsAuthenticated(true);
			return;
		}

		if (result === 'FRESH' && !afterFreshSignIn) {
			window.location.assign(`/oauth2/start?rd=${encodeURIComponent('/login?sso=1')}`);
			return;
		}

		setLoading(false);
		setErrorMessage(result === 'FRESH'
			? 'The Synology sign-in did not reach Race Manager. Check that the sign-in proxy passes it on (--pass-authorization-header=true).'
			: message ?? 'Synology sign-in failed');
	};

	useEffect(() => {
		new RaceMgrApi().ssoEnabled().then(enabled => {
			setSsoEnabled(enabled);
			if (enabled && ssoReturn && !ssoTried.current && !props.isAuthenticated) {
				ssoTried.current = true;
				handleSsoLogin(true);
			}
		});
	}, []);

	const handleUserNameChange = (event: React.ChangeEvent<HTMLInputElement>) => {
		setUserName(event.target.value);
		setErrorMessage('');
	};

	const handlePasswordChange = (event: React.ChangeEvent<HTMLInputElement>) => {
		setPassword(event.target.value);
		setErrorMessage('');
	};

	const handleLogin = () => {
		setLoading(true);
		try	{
			doAuth(userName, password).then(res => {
				if (res === 'OK') {
					props.setIsAuthenticated(true);
				} else {
					setErrorMessage('Invalid username or password');
					setLoading(false);
				}
			});
		} catch {
			setLoading(false);
			setErrorMessage('Login Error');
		}
	};

	return (

		props.isAuthenticated === true ? <Navigate to="/" replace />

			: <Paper sx={{
				p: 2,
				pb: 5,
				display: 'flex',
				flexDirection: 'column',
			}}>
				<Typography variant="h5" align="left" gutterBottom={true}>
				Login
				</Typography>

				{ssoEnabled && <>
					<Typography variant="subtitle1" align="left" gutterBottom={true} sx={{maxWidth: 500}}>
						<Button variant="contained" onClick={() => handleSsoLogin(false)}>Sign in with Synology</Button>
					</Typography>
					<Divider sx={{maxWidth: 500, my: 2}}>or with a Race Manager account</Divider>
				</>}

				<Typography variant="subtitle1" align="left" gutterBottom={true} sx={{maxWidth: 500}}>
					<TextField
						slotProps={{htmlInput: {maxLength: 50}}}
						id="standard-basic"
						label="UserName"
						variant="standard"
						fullWidth
						required={true}
						value={userName}
						onChange={handleUserNameChange}
					/>
				</Typography>

				<Typography variant="subtitle1" align="left" gutterBottom={true} sx={{maxWidth: 500}}>
					<TextField
						slotProps={{htmlInput: {maxLength: 50}}}
						id="standard-basic"
						label="Password"
						variant="standard"
						fullWidth
						required={true}
						value={password}
						type={'password'}
						onChange={handlePasswordChange}
					/>
				</Typography>

				<Typography variant="subtitle1" align="left" gutterBottom={true} sx={{maxWidth: 500}}>
					<ErrorTypography>
						{errorMessage}
					</ErrorTypography>
				</Typography>

				<Typography variant="subtitle1" align="left" gutterBottom={true} sx={{maxWidth: 500}}>
					<Button disabled={userName === '' || password === ''} variant="outlined" onClick={handleLogin}>Login</Button>
				</Typography>

				<Backdrop
					sx={{color: '#fff', zIndex: theme => theme.zIndex.drawer + 1}}
					open={loading}
				>
					<CircularProgress color="inherit" />
				</Backdrop>

			</Paper>
	);
};

export default Login;
