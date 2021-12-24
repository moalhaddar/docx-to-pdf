import { readFile } from "fs"


export const get_file: (path: string, sk: (data: Buffer) => void, fk: (err: NodeJS.ErrnoException) => void) => void = (path, sk, fk) => {
	readFile(path, (err, data) => {
		if (err){
			fk(err);
			return;
		}
		sk(data);
	})
}