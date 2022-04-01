import { delete_file } from "./delete_file";
import { is_file_exist } from "./is_file_exist";
import { logger_function_type } from "./make_logger";

export const cleanup_file: 
	(file_path: string, logger: logger_function_type, fk: (error: NodeJS.ErrnoException) => void, sk: () => void) => void 
		= (file_path, logger, fk, sk) => 
			is_file_exist(
				file_path,
				logger,
				fk,
				() => delete_file(file_path, logger, fk, sk),
			)

export const cleanup_files: (file_paths: string[], logger: logger_function_type, fk: (step: string) => void, sk: () => void) => void 
	= (file_paths, logger, fk, sk) => {
		Promise.all(
			file_paths
			.map(file_path => 
				new Promise<void>((res, rej) => 
					cleanup_file(file_path, logger, rej, () => res())
				)
			)
		).then(sk)
		.catch(() => fk('cleaning files'))
	}
