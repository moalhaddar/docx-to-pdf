import { CLEANUP_AUTOMATION_DRYMODE, CLEANUP_AUTOMATION_INTERVAL_MS, FILE_MAX_AGE_IN_SECONDS, TEMPORARY_PDF_PATH, TEMPORARY_UPLOAD_PATH } from "../constants";
import { cleanup_files } from "../functions/delete_file_if_exist";
import { get_files_in_dirs } from "../functions/get_files_in_dir";
import { get_files_older_than_n_seconds } from "../functions/get_files_older_than";
import { logger_function_type, make_logger } from "../functions/make_logger";

const prepare_for_next_cleanup = (logger: logger_function_type) => {
	const next_run_time = new Date(
		new Date().getTime() + CLEANUP_AUTOMATION_INTERVAL_MS
	).toISOString()

	logger(`Next cleanup automation at ${next_run_time}`);

	setTimeout(() => {
		start_cleanup_automation_bot();
	}, CLEANUP_AUTOMATION_INTERVAL_MS)
}


export const start_cleanup_automation_bot: () => void = () => {
	const logger = make_logger(`[AUTOMATION]`, `[CLEANUP]`);
	logger('Starting Cleanup Automation')
	const sk = () => {
		logger('Cleanup Automation finished');
		prepare_for_next_cleanup(logger);
	}

	const fk = () => {
		logger('Cleanup automation failed');
		prepare_for_next_cleanup(logger);
	}

	get_files_in_dirs(
		[TEMPORARY_PDF_PATH, TEMPORARY_UPLOAD_PATH],
		(all_file_paths) => {
			get_files_older_than_n_seconds(
				all_file_paths, 
				FILE_MAX_AGE_IN_SECONDS,
				logger,
				fk,
				(file_paths_older_than_age) => {
					if (CLEANUP_AUTOMATION_DRYMODE){
						logger(`[DRY MODE]`,`Cleaning ${file_paths_older_than_age.length} files\n`, file_paths_older_than_age);
						sk();
					} else {
						cleanup_files(
							file_paths_older_than_age,
							logger,
							fk,
							sk
						)	
					}
				}
			)
		},
		fk
	)
}