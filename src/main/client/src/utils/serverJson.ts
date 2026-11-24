// Reads the game server's own relaxed JSON, as in server.cfg or lua_config/sms_rotate_config.json:
// `//`, `/* */` and `#` comments, bare keys (`version : 7`), optional or trailing commas, and a top level that is
// a list of `key : value` pairs without the surrounding braces. Plain JSON reads the same. Throws on anything else.
export const parseServerJson = (text: string): unknown => {
	let i = 0;

	const fail = (what: string): never => {
		const line = text.slice(0, i).split('\n').length;
		throw new Error(`${what} on line ${line}`);
	};

	const skip = () => {
		while (i < text.length) {
			const c = text[i];
			if (c === ' ' || c === '\t' || c === '\r' || c === '\n' || c === '﻿') {
				i++;
			} else if (c === '#' || (c === '/' && text[i + 1] === '/')) {
				while (i < text.length && text[i] !== '\n') {
					i++;
				}
			} else if (c === '/' && text[i + 1] === '*') {
				const end = text.indexOf('*/', i + 2);
				i = end < 0 ? fail('Unclosed comment') : end + 2;
			} else {
				return;
			}
		}
	};

	const string = (): string => {
		let out = '';
		i++; // Opening quote
		while (i < text.length && text[i] !== '"') {
			if (text[i] === '\\') {
				const escaped = JSON.parse(`"${text.slice(i, i + (text[i + 1] === 'u' ? 6 : 2))}"`) as string;
				out += escaped;
				i += text[i + 1] === 'u' ? 6 : 2;
			} else if (text[i] === '\n') {
				fail('Line break inside text');
			} else {
				out += text[i++];
			}
		}

		if (text[i] !== '"') {
			fail('Unclosed text');
		}

		i++;
		return out;
	};

	const word = (): string => {
		const match = /^[A-Za-z_][A-Za-z0-9_.-]*/.exec(text.slice(i));
		if (!match) {
			fail('Unexpected character');
		}

		i += match![0].length;
		return match![0];
	};

	const key = (): string => (text[i] === '"' ? string() : word());

	// Members until `end` ("}" or the end of the text for the brace-less top level)
	const members = (end: string | null): Record<string, unknown> => {
		const result: Record<string, unknown> = {};
		for (;;) {
			skip();
			if (end ? text[i] === end : i >= text.length) {
				if (end) {
					i++;
				}

				return result;
			}

			if (i >= text.length) {
				fail('Missing }');
			}

			const name = key();
			skip();
			if (text[i] !== ':') {
				fail(`Missing : after ${name}`);
			}

			i++;
			result[name] = value();
			skip();
			if (text[i] === ',') {
				i++;
			}
		}
	};

	const array = (): Array<unknown> => {
		const result: Array<unknown> = [];
		i++; // [
		for (;;) {
			skip();
			if (text[i] === ']') {
				i++;
				return result;
			}

			if (i >= text.length) {
				fail('Missing ]');
			}

			result.push(value());
			skip();
			if (text[i] === ',') {
				i++;
			}
		}
	};

	const value = (): unknown => {
		skip();
		const c = text[i];
		if (c === '{') {
			i++;
			return members('}');
		}

		if (c === '[') {
			return array();
		}

		if (c === '"') {
			return string();
		}

		const number = /^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?/.exec(text.slice(i));
		if (number) {
			i += number[0].length;
			return Number(number[0]);
		}

		const bare = word();
		if (bare === 'true' || bare === 'false') {
			return bare === 'true';
		}

		if (bare === 'null') {
			return null;
		}

		return fail(`Unexpected ${bare}`);
	};

	skip();
	const result = text[i] === '{' || text[i] === '[' ? value() : members(null);
	skip();
	if (i < text.length) {
		fail('Unexpected text after the end');
	}

	return result;
};
