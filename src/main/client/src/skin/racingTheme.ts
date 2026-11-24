import {createTheme} from '@mui/material/styles';

import '@fontsource/titillium-web/400.css';
import '@fontsource/titillium-web/600.css';
import '@fontsource/titillium-web/700.css';
import '@fontsource/titillium-web/700-italic.css';

import {RACING_RED, RACING_YELLOW, TIMING_GREEN} from './colors';

const heading = {fontWeight: 700, fontStyle: 'italic', textTransform: 'uppercase' as const, letterSpacing: '0.04em'};

// The racing look: always dark, translucent panels over the background photo, motorsport type and timing colours
const racingTheme = createTheme({
	cssVariables: {colorSchemeSelector: 'class'},
	defaultColorScheme: 'dark',
	colorSchemes: {
		dark: {
			palette: {
				primary: {main: RACING_RED},
				secondary: {main: RACING_YELLOW},
				success: {main: TIMING_GREEN},
				background: {default: '#08080a', paper: 'rgba(16, 16, 20, 0.88)'},
				text: {primary: '#f4f4f4', secondary: '#a9a9b3'},
				divider: 'rgba(255, 255, 255, 0.10)',
			},
		},
	},
	typography: {
		fontFamily: '"Titillium Web", Roboto, Helvetica, Arial, sans-serif',
		h4: heading,
		h5: heading,
		h6: {...heading, fontSize: '1.05rem'},
		subtitle2: {fontWeight: 700, letterSpacing: '0.03em'},
		button: {fontWeight: 700, letterSpacing: '0.06em'},
	},
	shape: {borderRadius: 2},
	components: {
		MuiPaper: {
			styleOverrides: {
				root: {
					backgroundImage: 'none',
					backdropFilter: 'blur(6px)',
					border: '1px solid rgba(255, 255, 255, 0.06)',
				},
			},
		},
		MuiAppBar: {
			styleOverrides: {
				root: {
					backgroundColor: '#050506',
					backgroundImage: 'linear-gradient(90deg, #050506 0%, #16161a 60%, #050506 100%)',
					// The chequered flag along the bottom of the bar
					'&::after': {
						content: '""', position: 'absolute', left: 0, right: 0, bottom: 0, height: 8,
						backgroundImage: 'conic-gradient(#f4f4f4 25%, #0a0a0a 0 50%, #f4f4f4 0 75%, #0a0a0a 0)',
						backgroundSize: '8px 8px',
					},
				},
			},
		},
		MuiDrawer: {
			styleOverrides: {
				paper: {backgroundColor: 'rgba(8, 8, 10, 0.92)', borderRight: `2px solid ${RACING_RED}`},
			},
		},
		MuiTableCell: {
			styleOverrides: {
				head: {
					backgroundColor: '#121216', color: '#f4f4f4', fontWeight: 700, textTransform: 'uppercase',
					fontSize: '0.75rem', letterSpacing: '0.06em', borderBottom: `2px solid ${RACING_RED}`,
				},
				body: {borderColor: 'rgba(255, 255, 255, 0.06)'},
			},
		},
		MuiTableRow: {
			styleOverrides: {
				root: {'&:nth-of-type(even) > td, &:nth-of-type(even) > th': {backgroundColor: 'rgba(255, 255, 255, 0.025)'}},
			},
		},
		MuiAccordion: {
			styleOverrides: {
				root: {backgroundColor: 'rgba(20, 20, 24, 0.9)', '&::before': {backgroundColor: 'rgba(255, 255, 255, 0.08)'}},
			},
		},
		MuiTab: {
			styleOverrides: {root: {fontWeight: 700, letterSpacing: '0.06em'}},
		},
		MuiTabs: {
			styleOverrides: {indicator: {height: 3, backgroundColor: RACING_RED}},
		},
	},
});

export default racingTheme;
