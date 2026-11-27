import React, {useEffect, useState} from 'react';
import {Link as RouterLink, useSearchParams} from 'react-router-dom';

import RaceMgrApi from '../../api/RaceMgrApi';

import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';

const isLocalCallback = (raw: string | null): raw is string => {
	if (!raw) {
		return false;
	}

	try {
		const url = new URL(raw);
		return url.protocol === 'http:' && (url.hostname === '127.0.0.1' || url.hostname === 'localhost');
	} catch {
		return false;
	}
};

// Browser handoff for SimHub: once signed in (via Synology SSO or password), sends the user's JWT back to localhost
const SimhubAuthPage: React.FC = () => {
	const [params] = useSearchParams();
	const callback = params.get('callback');
	const validCallback = isLocalCallback(callback);
	const [status, setStatus] = useState<'checking' | 'login' | 'error'>('checking');
	const [error, setError] = useState('');

	useEffect(() => {
		if (!validCallback) {
			return;
		}

		const rmApi = new RaceMgrApi();
		if (!rmApi.hasSession()) {
			setStatus('login');
			return;
		}

		rmApi.makeApiCall({url: `${rmApi.getBaseAdminApiUrl()}/ingest/token`, method: 'get'})
			.then(resp => {
				const token = resp.data?.data?.token;
				const user = resp.data?.data?.username ?? '';
				if (!token) {
					setStatus('login');
					return;
				}

				const sep = callback.includes('?') ? '&' : '?';
				window.location.href = `${callback}${sep}token=${encodeURIComponent(token)}&user=${encodeURIComponent(user)}`;
			})
			.catch(() => {
				setError('Could not retrieve an ingest token. Make sure your account has Steward or Admin access.');
				setStatus('error');
			});
	}, [callback, validCallback]);

	if (!validCallback) {
		return (
			<Alert severity="error" sx={{textAlign: 'left'}}>
				Missing or non-local SimHub callback URL. Start sign-in from the SimHub plugin settings.
			</Alert>
		);
	}

	return (
		<Paper sx={{p: 3, maxWidth: 520, mx: 'auto', textAlign: 'left'}}>
			<Typography variant="h5" gutterBottom>Connect SimHub to Race Manager</Typography>
			{status === 'checking' && <Typography>Signing in and returning token to SimHub...</Typography>}
			{status === 'login' && (
				<>
					<Typography variant="body2" color="text.secondary" sx={{mb: 2}}>
						Sign in first (with Synology SSO or your Race Manager account), then return to this tab or click Sign in again from SimHub.
					</Typography>
					<Button variant="contained" component={RouterLink} to="/login">Sign in</Button>
				</>
			)}
			{status === 'error' && <Alert severity="error">{error}</Alert>}
		</Paper>
	);
};

export default SimhubAuthPage;
