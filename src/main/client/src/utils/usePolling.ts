import {useEffect, useRef} from 'react';

// Runs `callback` now and then every `intervalMs` while the component is mounted and the tab is visible.
export const usePolling = (callback: () => void, intervalMs: number) => {
	const saved = useRef(callback);
	saved.current = callback;

	useEffect(() => {
		let timer: ReturnType<typeof setInterval> | undefined;

		const start = () => {
			saved.current();
			timer = setInterval(() => saved.current(), intervalMs);
		};

		const stop = () => {
			if (timer) {
				clearInterval(timer);
				timer = undefined;
			}
		};

		const onVisibility = () => {
			stop();
			if (!document.hidden) {
				start();
			}
		};

		if (!document.hidden) {
			start();
		}

		document.addEventListener('visibilitychange', onVisibility);
		return () => {
			stop();
			document.removeEventListener('visibilitychange', onVisibility);
		};
	}, [intervalMs]);
};
