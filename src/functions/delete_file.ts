import { unlink } from "fs"

export const delete_file: (file_path: string, sk: () => void, fk: (error: NodeJS.ErrnoException) => void) => void = (file_path, sk, fk) => {
	console.log(`Deleting file "$${file_path}"`);
	unlink(file_path, (error) => {
		if (error){
			console.error(error);
			console.error(`Could not delete file "$${file_path}"`)
			return fk(error);
		} else{
			console.log(`Deleted file "$${file_path}" successfully`);
			return sk();
		}
	})
}