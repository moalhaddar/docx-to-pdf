import { delete_file } from "./delete_file";
import { is_file_exist } from "./is_file_exist";


export const cleanup_file: (file_path: string, sk: () => void, fk: (error: NodeJS.ErrnoException) => void) => void = (file_path, sk, fk) => 
	is_file_exist(
		file_path,
		() => delete_file(file_path, sk, fk),
		fk
	)


export const cleanup_files: (file_paths: string[], sk: () => void, fk: (error: NodeJS.ErrnoException) => void) => void = (file_paths, sk, fk) => 
	file_paths
		.reduceRight<() => void>(
			(next, file_path) => () => cleanup_file(file_path, next, fk),
			sk
		)()	
