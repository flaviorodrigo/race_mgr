// The racing skin's background photos: freely licensed pictures from Wikimedia Commons, served from this app
// (public/skin) and credited as their licences require (see the "About the images" page). Each was resized for the web.
export interface ISkinImage {
	file: string,
	driver: string,
	caption: string,
	author: string,
	license: string,
	licenseUrl: string,
	source: string,
	// Which part of the photo to keep in view when the screen crops it (CSS background-position)
	focus?: string,
}

export const SKIN_IMAGES: Array<ISkinImage> = [
	{
		file: '/skin/fittipaldi-penske-mid-ohio.jpg',
		driver: 'Emerson Fittipaldi',
		caption: 'Emerson Fittipaldi, Penske, Mid-Ohio',
		author: 'Rick Dikeman',
		license: 'CC0 1.0 (public domain dedication)',
		licenseUrl: 'https://creativecommons.org/publicdomain/zero/1.0/',
		source: 'https://commons.wikimedia.org/wiki/File:Emerson-fittipaldi_mid-ohio_keyhole.jpg',
		focus: 'center 65%',
	},
	{
		file: '/skin/piquet-brabham-monaco-1980.jpg',
		driver: 'Nelson Piquet',
		caption: 'Nelson Piquet, Brabham BT49, Monaco 1980',
		author: 'Carlomarzetti',
		license: 'CC BY 4.0',
		licenseUrl: 'https://creativecommons.org/licenses/by/4.0/',
		source: 'https://commons.wikimedia.org/wiki/File:Nelson_Piquet_Brabham_1980_Monaco.jpg',
		focus: 'center 60%',
	},
	{
		file: '/skin/senna-mclaren-phoenix-1991.jpg',
		driver: 'Ayrton Senna',
		caption: 'Ayrton Senna, McLaren MP4/6, Phoenix 1991',
		author: 'wileynorwichphoto',
		license: 'CC BY 2.0',
		licenseUrl: 'https://creativecommons.org/licenses/by/2.0/',
		source: 'https://commons.wikimedia.org/wiki/File:Ayrton_Senna_McLaren_MP4-6_1991_United_States.jpg',
		focus: 'center 40%',
	},
	{
		file: '/skin/schumacher-ferrari-indianapolis-2004.jpg',
		driver: 'Michael Schumacher',
		caption: 'Michael Schumacher, Ferrari, Indianapolis 2004',
		author: 'Rick Dikeman',
		license: 'CC BY-SA 3.0 (the resized copy here is shared under the same licence)',
		licenseUrl: 'https://creativecommons.org/licenses/by-sa/3.0/',
		source: 'https://commons.wikimedia.org/wiki/File:Michael_Schumacher_Ferrari_2004.jpg',
		focus: 'center 55%',
	},
];
