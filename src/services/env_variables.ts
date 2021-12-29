
const load_env_variable: (key: string) => string = (key) => {
	const value = process.env[key];
	if (!value){
		throw new Error(`Could not load environment variable: ${key}`);
	}
	return value;
}

export const ENV_CLEANUP_AUTOMATION_DRY_MODE = load_env_variable('CLEANUP_AUTOMATION_DRY_MODE');
export const ENV_CLEANUP_AUTOMATION_INTERVAL_MS = load_env_variable('CLEANUP_AUTOMATION_INTERVAL_MS');
export const ENV_FILE_MAX_AGE_IN_SECONDS = load_env_variable('FILE_MAX_AGE_IN_SECONDS');
export const ENV_PORT = load_env_variable('PORT');