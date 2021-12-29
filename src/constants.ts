import { ENV_CLEANUP_AUTOMATION_DRY_MODE, ENV_CLEANUP_AUTOMATION_INTERVAL_MS, ENV_FILE_MAX_AGE_IN_SECONDS, ENV_PORT } from "./services/env_variables";

export const TEMPORARY_PDF_PATH = "/tmp/generated_pdfs";
export const TEMPORARY_UPLOAD_PATH = '/tmp/uploaded_docx';
export const PORT: number = Number(ENV_PORT);
export const FILE_MAX_AGE_IN_SECONDS: number = Number(ENV_FILE_MAX_AGE_IN_SECONDS);
export const CLEANUP_AUTOMATION_INTERVAL_MS: number = Number(ENV_CLEANUP_AUTOMATION_INTERVAL_MS);
export const CLEANUP_AUTOMATION_DRYMODE: boolean = ENV_CLEANUP_AUTOMATION_DRY_MODE === 'ON';