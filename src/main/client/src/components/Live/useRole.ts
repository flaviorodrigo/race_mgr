import {useEffect, useState} from 'react';

import RaceMgrApi from '../../api/RaceMgrApi';
import {Role} from '../../utils/roles';
import {fetchMe} from './adminApi';

// Who is signed in and what role they have; role stays null for a visitor or until the server has answered.
export const useRole = (): {role: Role | null, username: string | null, loaded: boolean} => {
	const [me, setMe] = useState<{role: Role | null, username: string | null}>({role: null, username: null});
	const [loaded, setLoaded] = useState(false);

	useEffect(() => {
		let cancelled = false;
		if (!new RaceMgrApi().hasSession()) {
			setLoaded(true);
			return undefined;
		}

		fetchMe().then(result => {
			if (!cancelled) {
				setMe(result.ok && result.data ? {role: result.data.role, username: result.data.username} : {role: null, username: null});
				setLoaded(true);
			}
		});
		return () => {
			cancelled = true;
		};
	}, []);

	return {...me, loaded};
};
