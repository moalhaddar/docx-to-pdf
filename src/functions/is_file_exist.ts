import {stat} from "fs";

export const is_file_exist: (file_path: string, sk: () => void, fk: (error: Error) => void) => void = (file_path, sk, fk) => {
	console.log(`Looking up file: ${file_path}`)
	stat(file_path, (error) => {
		if (error){
			console.error(`File not found: ${file_path}`)
			return fk(error);
		}
		console.log(`File found: ${file_path}`)
		return sk();
	})
}