import React, {useState} from 'react';

import IconButton from '@mui/material/IconButton';
import Snackbar from '@mui/material/Snackbar';
import Tooltip from '@mui/material/Tooltip';
import LinkIcon from '@mui/icons-material/Link';

// Copies the address of a race's own page so it can be shared
const CopyLinkButton = ({raceId}: {raceId: string}) => {
	const [message, setMessage] = useState('');

	const copy = async () => {
		const url = `${window.location.origin}/race/${raceId}`;
		try {
			await navigator.clipboard.writeText(url);
			setMessage('Link copied');
		} catch {
			// Clipboard access needs HTTPS or localhost; show the address so it can be copied by hand
			setMessage(url);
		}
	};

	return (
		<>
			<Tooltip title="Copy a link to this race">
				<IconButton size="small" aria-label="Copy link to this race" onClick={copy}>
					<LinkIcon fontSize="small" />
				</IconButton>
			</Tooltip>
			<Snackbar open={message !== ''} autoHideDuration={6000} message={message} onClose={() => setMessage('')} />
		</>
	);
};

export default CopyLinkButton;
