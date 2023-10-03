import { exec, ExecException } from "child_process";
import { TEMPORARY_LIBREOFFICE_PROFILES_PATH, TEMPORARY_PDF_PATH } from "../constants";
import { logger_function_type } from "./make_logger";

const CHARS = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'

const random_str: (length: number) => string = (length) => {
	return Array(length)
		.fill(0)
		.map(() => CHARS[(Math.floor(Math.random() * CHARS.length)) - 1])
		.join('');
}
	

export const doc_to_pdf_cli: (docx_path: string, logger: logger_function_type, fk: (err: ExecException) => void, sk: (pdfPath: string) => void) => void 
	= (docx_path, logger, fk, sk) => 
		// docx path is a temporary file, not a user input
		exec(`
			libreoffice \
				-env:UserInstallation=file://${TEMPORARY_LIBREOFFICE_PROFILES_PATH}/${random_str(20)} \
				--headless \
				--convert-to pdf:writer_pdf_Export \
				--outdir ${TEMPORARY_PDF_PATH} \
				"${docx_path}" 
			`, (err) => {
				if (err) {
					fk(err);
					return;
				}
				const docx_name = docx_path.split('/')[3];
				logger(`Converted ${docx_name} successfully`);
				const pdf_path = `${TEMPORARY_PDF_PATH}/${docx_name}.pdf`
				sk(pdf_path);
			}
		)
		