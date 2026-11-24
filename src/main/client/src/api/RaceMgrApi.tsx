import axios, {AxiosRequestConfig, AxiosResponse} from 'axios';

export default class RaceMgrApi {
	private readonly BASE_API_URL = '/api';
	private readonly BASE_API_URL_WITH_VERSION = `${this.BASE_API_URL}/v1`;
	private readonly BASE_ADMIN_URL = `${this.BASE_API_URL_WITH_VERSION}/admin`;

	private checkIfXsrfTokenIsRequired(config: AxiosRequestConfig): boolean {
		let xsrfTokenRequired = false;
		const methodsOfInterest = ['post', 'put', 'patch', 'delete'];
		if (config.method) {
			const method = config.method.toLocaleLowerCase();
			if (methodsOfInterest.indexOf(method) > -1) {
				xsrfTokenRequired = true;
			}
		} else {
			throw new Error('Request method unknown!');
		}

		return xsrfTokenRequired;
	}

	// Called before any API endpoint that requires manipulation (i.e.) POST/PUT/PATCH/DELETE
	private async setXsrfTokenIfNeeded(config: AxiosRequestConfig): Promise<any> {
		const xsrfTokenIsRequired = this.checkIfXsrfTokenIsRequired(config);
		if (xsrfTokenIsRequired) {
			// Just hit the unauthenticated root to get a generated XSRF-TOKEN

			const tokenRequestConfig = {
				method: 'get',
				url: this.BASE_API_URL,
			};

			// Response.headers['Set-Cookie'] will have the XSRF-TOKEN cookie
			// This gets called by the browser automatically so the next request will
			// already have the XSRF-TOKEN set now, nothing else needed.

			await axios(tokenRequestConfig);
		}

		return Promise.resolve();
	}

	// Admin requests authenticate with the HttpOnly session cookie the server sets at login (script can't
	// read it, so there is no token to attach). RM-SESSION is a readable marker that a session exists.
	private requireSessionIfAdmin(config: AxiosRequestConfig = {}) {
		if (config.url && config.url.includes('/admin') && !this.hasSession()) {
			throw new Error('Admin endpoint requested, but there is no session. Call doAuth first.');
		}
	}

	public getBaseApiUrl() {
		return this.BASE_API_URL_WITH_VERSION;
	}

	public getBaseAdminApiUrl() {
		return this.BASE_ADMIN_URL;
	}

	// Public endpoints need no auth, else I would have made these inputs
	// request to init the class. So just call once before making any admin
	// requests and it will keep the token in memory for subsequent requests.
	public async doAuth(username: string, password: string): Promise<string> {
		return new Promise(async (resolve, reject) => {
			const data = JSON.stringify({
				username,
				password,
			});

			const config: AxiosRequestConfig = {
				method: 'post',
				url: `${this.BASE_API_URL_WITH_VERSION}/authenticate`,
				headers: {
					'Content-Type': 'application/json',
				},
				data,
			};

			await this.setXsrfTokenIfNeeded(config);

			try	{
				const response = await axios(config);
				if (response.status === 200) {
					resolve('OK');
				} else {
					resolve('Error');
				}
			} catch {
				resolve('Error');
			}
		});
	}

	// Whether the login page offers "Sign in with Synology" (RM_SSO_ISSUER and RM_SSO_CLIENT_ID are set)
	public async ssoEnabled(): Promise<boolean> {
		try {
			const response = await axios({method: 'get', url: `${this.BASE_API_URL_WITH_VERSION}/sso`});
			return response?.data?.data?.enabled === true;
		} catch {
			return false;
		}
	}

	// Turns the Synology sign-in the proxy forwards into a Race Manager session. 'FRESH' means the forwarded
	// sign-in is missing or too old (the SSO Server's tokens only last minutes): sign in with Synology again.
	public async doSsoLogin(): Promise<{result: 'OK' | 'FRESH' | 'ERROR', message?: string}> {
		const config: AxiosRequestConfig = {method: 'post', url: `${this.BASE_API_URL_WITH_VERSION}/sso/login`};
		await this.setXsrfTokenIfNeeded(config);
		try {
			await axios(config);
			return {result: 'OK'};
		} catch (error: any) {
			const body = error?.response?.data;
			if (error?.response?.status === 401 && body?.data?.freshSignIn) {
				return {result: 'FRESH'};
			}

			return {result: 'ERROR', message: typeof body?.message === 'string' ? body.message : undefined};
		}
	}

	// This is the general method you'd call most often. In the config
	// obj it expects url and method at least. It will automatically set
	// xsrf tokens as needed and also add the JWT as needed (assuming doAuth
	// was previously called).
	public async makeApiCall(config: AxiosRequestConfig): Promise<any> {
		return this.setXsrfTokenIfNeeded(config).then(() => {
			this.requireSessionIfAdmin(config);
			return axios(config);
		}).catch(async error => {
			// An expired or invalid session: drop it and send the user to the login page
			if (error?.response?.status === 401 && config.url?.includes('/admin')) {
				await this.doLogout();
				window.location.assign('/login');
			}

			throw error;
		});
	}

	// Ends the session: the server expires the cookies
	public async doLogout(): Promise<void> {
		try {
			await this.setXsrfTokenIfNeeded({method: 'post'});
			await axios({method: 'post', url: `${this.BASE_API_URL_WITH_VERSION}/logout`});
		} catch {
			// Nothing else to do, the session cookie expires on its own
		}
	}

	public hasSession(): boolean {
		return this.getCookie('RM-SESSION') === '1';
	}

	private getCookie(name) {
		const value = `; ${document.cookie}`;
		const parts = value.split(`; ${name}=`);
		if (parts.length === 2) {
			const val = parts.pop();
			if (val) {
				return val.split(';').shift();
			}
		}

		return '';
	}
}
