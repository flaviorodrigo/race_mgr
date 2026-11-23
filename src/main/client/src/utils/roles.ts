// What a signed-in person may do; each role includes the ones below it. The server enforces this: the UI only
// hides what a person could not use anyway.
export type Role = 'STEWARD' | 'ADMIN' | 'OWNER';

const RANK: Record<Role, number> = {STEWARD: 1, ADMIN: 2, OWNER: 3};

export const atLeast = (role: Role | null | undefined, needed: Role): boolean => Boolean(role) && RANK[role as Role] >= RANK[needed];

export const roleLabel = (role: string): string => ({OWNER: 'Owner', ADMIN: 'Admin', STEWARD: 'Steward'}[role] ?? role);

// A random password for the owner to hand over: letters and digits without look-alikes
export const generatePassword = (length = 16): string => {
	const alphabet = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789';
	const limit = 256 - (256 % alphabet.length); // Values above this would favour the first characters
	let result = '';
	while (result.length < length) {
		for (const byte of crypto.getRandomValues(new Uint8Array(length))) {
			if (byte < limit && result.length < length) {
				result += alphabet[byte % alphabet.length];
			}
		}
	}

	return result;
};
