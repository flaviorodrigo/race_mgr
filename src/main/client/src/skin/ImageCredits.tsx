import React from 'react';

import {SKIN_IMAGES} from './images';

import Box from '@mui/material/Box';
import Link from '@mui/material/Link';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';

// The credits the background photos' licences ask for: who took them, under which licence, and where they come from
const ImageCredits = () => (
	<Paper sx={{p: 2, textAlign: 'left'}}>
		<Typography variant="h5" component="h2" gutterBottom>About the images</Typography>
		<Typography variant="body2" sx={{mb: 2}}>
			The racing look shows freely licensed photos from Wikimedia Commons. Each was resized for the web; the originals and their full
			licence terms are on the linked pages. The drivers, teams and sponsors shown don&apos;t endorse Race Manager.
		</Typography>
		{SKIN_IMAGES.map(image => (
			<Box key={image.file} sx={{display: 'flex', gap: 2, alignItems: 'center', mb: 2, flexWrap: 'wrap'}}>
				<Box component="img" src={image.file} alt={image.caption} sx={{width: 200, height: 112, objectFit: 'cover', borderRadius: 1}} />
				<Box>
					<Typography variant="subtitle2">{image.caption}</Typography>
					<Typography variant="body2">Photo: {image.author}</Typography>
					<Typography variant="body2">
						Licence: <Link href={image.licenseUrl} target="_blank" rel="noopener noreferrer">{image.license}</Link>
						{' · '}<Link href={image.source} target="_blank" rel="noopener noreferrer">Original on Wikimedia Commons</Link>
					</Typography>
				</Box>
			</Box>
		))}
	</Paper>
);

export default ImageCredits;
