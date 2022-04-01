import { stat } from "fs";
import { logger_function_type } from "./make_logger";

type FileMeta = {
	file_path: string;
	age_in_seconds: number;
}

const get_file_age_in_seconds: (file_path: string) => Promise<number> = (file_path) => 
	new Promise(
		(res, rej) => 
			stat(file_path, (err, meta) =>{
				if (err){
					console.error(err);
					return rej();
				}
				const ms_to_sec = (n: number) => n / 1000;

				const currentTime = ms_to_sec(new Date().getTime());
				const fileCreationTime = ms_to_sec(meta.ctime.getTime());
				return res(currentTime - fileCreationTime);
			})	
	)

export const get_files_older_than_n_seconds: 
	(file_paths: string[], target_age_in_seconds: number, logger: logger_function_type, fk: (step: string) => void, sk: (file_paths_older_than: string[]) => void) => void
	= (file_paths, target_age_in_seconds, logger, fk, sk) => {
		Promise.all(
			file_paths
			.map(
				file_path => new Promise<FileMeta>(
					(res, rej) => 
						get_file_age_in_seconds(file_path)
						.then(computed_age => 
							res({file_path, age_in_seconds: computed_age})
						)
						.catch(rej)
				)
			)
		)
		.then(
			(metas) => {
				const old_files = metas
					.filter(meta => meta.age_in_seconds >= target_age_in_seconds)
					.map(({file_path}) => file_path)
				logger(`Number of old files found: ${old_files.length}`);
				sk(old_files);
			}
		).catch(() => fk('getting files older than n seconds'))
	}