import { stat, Stats } from "fs";

export const get_file_metadata: (file_path: string, sk: (meta_data: Stats) => void, fk: () => void) => void = (file_path, sk, fk) => {
	stat(file_path, (err, meta) =>{
		if (err){
			console.error(err);
			return fk();
		}
		return sk(meta);
	})
}

