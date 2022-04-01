import { stat } from "fs";
import { logger_function_type } from "./make_logger";

export const is_file_exist: (file_path: string, logger: logger_function_type, fk: (error: Error) => void, sk: () => void) => void 
	= (file_path, logger, fk, sk) => {
		// logger(`Looking up file: ${file_path}`)
		stat(file_path, (error) => {
			if (error){
				console.error(`File not found: ${file_path}`)
				return fk(error);
			}
			// logger(`File found: ${file_path}`)
			return sk();
		})
	}