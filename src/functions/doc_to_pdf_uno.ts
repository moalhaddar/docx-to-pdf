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
	

export const doc_to_pdf_uno: (docx_path: string, logger: logger_function_type, fk: (err: ExecException) => void, sk: (pdfPath: string) => void) => void 
	= (docx_path, logger, fk, sk) => {
		const docx_name = docx_path.split('/')[3];
		const pdf_path = `${TEMPORARY_PDF_PATH}/${docx_name}.pdf`

		exec(`
			python3 -m unoserver.converter \
				--convert-to pdf \
				--filter writer_pdf_Export \
				"${docx_path}" \
				"${pdf_path}" \
			`, (err) => {
				if (err) {
					fk(err);
					return;
				}
				logger(`Converted ${docx_name} successfully`);
				sk(pdf_path);
			}
		)
	}
		