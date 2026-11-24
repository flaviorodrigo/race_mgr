import React, {useEffect, useState} from 'react';
import {Link as RouterLink} from 'react-router-dom';

import {SKIN_IMAGES} from './images';

import Box from '@mui/material/Box';
import Link from '@mui/material/Link';

const ROTATE_MS = 45_000;

// The legends behind the pages: one photo at a time, slowly crossfading to the next, darkened so text stays readable.
// The credit for the photo on screen is always shown, as the licences require.
const RacingBackdrop = () => {
	const [index, setIndex] = useState(() => Math.floor(Math.random() * SKIN_IMAGES.length));
	// Only the photo on screen and the one fading out are loaded, not all of them up front
	const [previous, setPrevious] = useState<number | null>(null);

	useEffect(() => {
		if (SKIN_IMAGES.length < 2) {
			return undefined;
		}

		const timer = setInterval(() => setIndex(i => {
			setPrevious(i);
			return (i + 1) % SKIN_IMAGES.length;
		}), ROTATE_MS);
		return () => clearInterval(timer);
	}, []);

	const current = SKIN_IMAGES[index];
	return (
		<>
			<Box aria-hidden sx={{position: 'fixed', inset: 0, zIndex: -1, backgroundColor: '#08080a'}}>
				{SKIN_IMAGES.map((image, i) => (i === index || i === previous) && (
					<Box key={image.file} sx={{
						position: 'absolute', inset: 0,
						backgroundImage: `url(${image.file})`, backgroundSize: 'cover', backgroundPosition: image.focus ?? 'center',
						opacity: i === index ? 1 : 0, transition: 'opacity 2.5s ease-in-out',
						'@media (prefers-reduced-motion: reduce)': {transition: 'none'},
					}} />
				))}
				{/* Darkening and a fine carbon weave over the photo */}
				<Box sx={{
					position: 'absolute', inset: 0,
					background: 'linear-gradient(180deg, rgba(8,8,10,0.55) 0%, rgba(8,8,10,0.80) 45%, rgba(8,8,10,0.92) 100%), '
						+ 'repeating-linear-gradient(45deg, rgba(255,255,255,0.018) 0 2px, transparent 2px 6px)',
				}} />
			</Box>
			<Box sx={{
				position: 'fixed', right: 8, bottom: 6, zIndex: theme => theme.zIndex.drawer + 2, fontSize: 11, px: 1, py: 0.25,
				color: 'rgba(255,255,255,0.75)', bgcolor: 'rgba(0,0,0,0.55)', borderRadius: '2px', maxWidth: 'calc(100vw - 16px)',
			}}>
				{current.caption} · Photo: {current.author}, {current.license.split(' (')[0]} ·{' '}
				<Link component={RouterLink} to="/about-images" color="inherit">About the images</Link>
			</Box>
		</>
	);
};

export default RacingBackdrop;
