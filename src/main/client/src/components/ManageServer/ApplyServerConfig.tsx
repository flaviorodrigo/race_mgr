import React, {useEffect} from 'react';
import {useSelector, useDispatch} from 'react-redux';

import {updateServerStatus} from '../../state/serverStatus';
import {getServerConfig, updateServerConfig} from '../../state/serverConfig';
import {fetchSessionDetail, updateServerConfig as saveActiveConfig, updateSession} from './utilities';

import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import ButtonGroup from '@mui/material/ButtonGroup';
import ArrowDropDownIcon from '@mui/icons-material/ArrowDropDown';
import ClickAwayListener from '@mui/material/ClickAwayListener';
import Grow from '@mui/material/Grow';
import Paper from '@mui/material/Paper';
import Popper from '@mui/material/Popper';
import MenuItem from '@mui/material/MenuItem';
import MenuList from '@mui/material/MenuList';

const options = ['Update Server', 'Start Server', 'Stop Server'];

interface IProps {
	showNotification: (message:string, severity: any) => void,
	setIsServerRunning: (isRunning:boolean) => void
}

const ApplyServerConfig = (props:IProps) => {
	const anchorRef = React.useRef<HTMLDivElement>(null);

	const [open, setOpen] = React.useState(false);
	const [selectedIndex, setSelectedIndex] = React.useState(1); // SplitButton
	const [formIsValid, setFormIsValid] = React.useState(true);

	const {serverStatus, serverConfig, sessions, rotations} = useSelector((state:any) => state);

	const dispatch = useDispatch();

	// When the game server isn't run by Race Manager there is nothing to start or stop from here
	const available = serverStatus.managed === false ? [options[0]] : options;

	useEffect(() => {
		const formIsValid
			= sessions.selectedSessionId !== ''
			&& sessions.selectedSessionId !== undefined
			&& rotations.selectedRotationId !== ''
			&& rotations.selectedRotationId !== undefined;
		setFormIsValid(formIsValid);
	}, [sessions.selectedSessionId, rotations.selectedRotationId]);

	const handleUpdateServerConfig = () => {
		dispatch(updateServerConfig(
			{
				activeRaceSessionId: sessions.selectedSessionId ?? '',
				activeRaceRotationId: rotations.selectedRotationId ?? '',
			}),
		);
	};

	// Only records which championship and rotation races belong to; the game server's rotation file is left alone
	const handleSetActive = async () => {
		const response: any = await saveActiveConfig({
			activeRaceSessionId: sessions.selectedSessionId ?? '',
			activeRaceRotationId: rotations.selectedRotationId ?? '',
		});
		let ok = response?.data?.status === 200;
		// The rotation joins the championship's list, so the leaderboard offers it under that championship
		if (ok) {
			const session: any = await fetchSessionDetail(sessions.selectedSessionId);
			const ids: Array<string> = session?.raceRotationIds ?? [];
			if (session && !ids.includes(rotations.selectedRotationId)) {
				const linked: any = await updateSession(session.name, sessions.selectedSessionId, [...ids, rotations.selectedRotationId]);
				ok = linked?.status === 200;
			}
		}

		props.showNotification(ok ? 'Set as active. New races are recorded under this championship and rotation.' : 'Could not set them as active', ok ? 'success' : 'error');
		if (ok) {
			dispatch(getServerConfig());
		}
	};

	const handleStopServer = () => {
		if (serverStatus.isRunning) {
			dispatch(updateServerStatus('stop'));
		}
	};

	const handleStartServer = () => {
		if (!serverStatus.isRunning) {
			dispatch(updateServerStatus('start'));
		}
	};

	const handleClick = () => {
		switch (options[selectedIndex]) {
			case 'Update Server':
				handleUpdateServerConfig();
				break;
			case 'Start Server':
				handleStartServer();
				break;
			case 'Stop Server':
				handleStopServer();
				break;
			default:
				break;
		}
	};

	const handleMenuItemClick = (
		event: React.MouseEvent<HTMLLIElement, MouseEvent>,
		index: number,
	) => {
		setSelectedIndex(index);
		setOpen(false);
	};

	const handleToggle = () => {
		setOpen(prevOpen => !prevOpen);
	};

	const handleClose = (event: Event) => {
		if (
			anchorRef.current
      && anchorRef.current.contains(event.target as HTMLElement)
		) {
			return;
		}

		setOpen(false);
	};

	useEffect(() => {
		setSelectedIndex(0);
	}, []);

	return (
		<React.Fragment>
			<Stack direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{alignItems: {sm: 'center'}}}>
				<Button variant="outlined" disabled={!formIsValid} onClick={handleSetActive}>Set active</Button>
				<ButtonGroup disabled={!formIsValid} variant="contained" ref={anchorRef} aria-label="split button">
					<Button onClick={handleClick}>{options[selectedIndex]}</Button>
					{available.length > 1 && <Button
						size="small"
						aria-controls={open ? 'split-button-menu' : undefined}
						aria-expanded={open ? 'true' : undefined}
						aria-label="select merge strategy"
						aria-haspopup="menu"
						onClick={handleToggle}
					>
						<ArrowDropDownIcon />
					</Button>}
				</ButtonGroup>
			</Stack>
			<Typography variant="body2" color="text.secondary" sx={{mt: 1, maxWidth: 760}}>
				<strong>Set active</strong> records new races under the selected championship and rotation, and changes nothing on the game
				server. <strong>Update Server</strong> does the same and also writes the rotation into the game server&apos;s rotation file,
				replacing the rotation it runs (from its next restart).
			</Typography>

			<Popper
				sx={{
					zIndex: 1,
				}}
				open={open}
				anchorEl={anchorRef.current}
				role={undefined}
				transition
				disablePortal
			>
				{({TransitionProps, placement}) => (
					<Grow
						{...TransitionProps}
						style={{
							transformOrigin:
                placement === 'bottom' ? 'center top' : 'center bottom',
						}}
					>
						<Paper>
							<ClickAwayListener onClickAway={handleClose}>
								<MenuList id="split-button-menu" autoFocusItem>
									{available.map((option, index) => (
										<MenuItem
											key={option}
											selected={index === selectedIndex}
											onClick={event => handleMenuItemClick(event, index)}
										>
											{option}
										</MenuItem>
									))}
								</MenuList>
							</ClickAwayListener>
						</Paper>
					</Grow>
				)}
			</Popper>
		</React.Fragment>
	);
};

export default ApplyServerConfig;
