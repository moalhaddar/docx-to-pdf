import { readdir } from "fs"
import path from "path"
import { flatten } from "./flatten";

export const get_files_in_dir: (dir_path: string, sk: (files_paths: string[]) => void, fk: () => void) => void = (dir_path, sk, fk) => 
	readdir(dir_path, 
		(err, file_names) => {
			if (err) {
				return fk();
			}
			return sk(file_names.map(file_name => path.join(dir_path, file_name)))
		}
)

export const get_files_in_dirs: (dir_paths: string[], sk: (file_paths: string[]) => void, fk: () => void) => void = (dir_paths, sk, fk) => 
	Promise.all(
		dir_paths.map(
			dir_path => new Promise<string[]>((res, rej) => 
				get_files_in_dir(dir_path, res, rej)
			)
		)
	)
	.then((files_per_dir) => sk(flatten(files_per_dir)))
	.catch(fk)