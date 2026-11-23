import {createTheme} from '@mui/material/styles';

// Light and dark colour schemes. The default follows the operating system setting; the app bar's
// toggle overrides it and MUI remembers the choice in localStorage.
const theme = createTheme({
	cssVariables: {colorSchemeSelector: 'class'},
	colorSchemes: {
		light: {
			palette: {
				primary: {main: '#ff2800'},
				secondary: {main: '#636262'},
				background: {default: '#f5f5f5'},
			},
		},
		dark: {
			palette: {
				primary: {main: '#ff5a3c'},
				secondary: {main: '#b0b0b0'},
				background: {default: '#121212', paper: '#1e1e1e'},
			},
		},
	},
});

export default theme;
