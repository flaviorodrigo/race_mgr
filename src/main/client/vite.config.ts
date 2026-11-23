import {defineConfig} from 'vitest/config';
import react from '@vitejs/plugin-react';

// The Maven build copies ./build into the Spring Boot jar's static resources.
export default defineConfig({
	plugins: [react()],
	build: {
		outDir: 'build',
		sourcemap: false,
		// Keep fonts and images as files (not data: URIs) so the CSP can stay strict
		assetsInlineLimit: 0,
	},
	server: {
		port: 3000,
		// Same-origin API in development, like the old Create React App "proxy" setting
		proxy: {
			'/api': 'http://localhost:8080',
		},
	},
	test: {
		environment: 'node',
	},
});
