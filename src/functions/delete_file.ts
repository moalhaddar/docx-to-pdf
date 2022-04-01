import { rm } from "fs"
import { logger_function_type } from "./make_logger";

export const delete_file: (file_path: string, logger: logger_function_type, fk: (error: NodeJS.ErrnoException) => void, sk: () => void, ) => void 
	= (file_path, logger, fk, sk) => {
		logger(`Deleting file "$${file_path}"`);
		rm(file_path, {recursive: true}, (error) => {
			if (error){
				console.error(error);
				console.error(`Could not delete file "$${file_path}"`)
				return fk(error);
			} else{
				logger(`Deleted file "${file_path}" successfully`);
				return sk();
			}
		})
	}