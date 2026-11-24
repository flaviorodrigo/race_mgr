import React, {createContext, useContext, useMemo, useState} from 'react';

// The "racing" look (dark carbon, chequered flag, legends in the background) or the classic light/dark theme.
// Each browser remembers its own choice; racing is the default.
const KEY = 'rm-skin';

interface ISkin {
	racing: boolean,
	setRacing: (racing: boolean) => void,
}

const SkinContext = createContext<ISkin>({racing: true, setRacing: () => undefined});

const stored = (): boolean => {
	try {
		return window.localStorage.getItem(KEY) !== 'classic';
	} catch {
		return true;
	}
};

export const SkinProvider = ({children}: {children: React.ReactNode}) => {
	const [racing, setRacingState] = useState(stored);
	const value = useMemo(() => ({
		racing,
		setRacing(on: boolean) {
			setRacingState(on);
			try {
				window.localStorage.setItem(KEY, on ? 'racing' : 'classic');
			} catch {
				// Private browsing: the choice lasts for this visit only
			}
		},
	}), [racing]);
	return <SkinContext.Provider value={value}>{children}</SkinContext.Provider>;
};

export const useSkin = () => useContext(SkinContext);
