import { Request } from "express";
import { UploadedFile } from "express-fileupload";
import { doc_to_pdf } from "../functions/doc_to_pdf";
import { get_file_data } from "../functions/get_file_data";
import { is_present } from "../functions/is_present";
import { logger_function_type } from "../functions/make_logger";

export const docx_to_pdf_handler: (
	files: Request['files'],
	logger: logger_function_type,
	fk: (args: {status: 400 | 500, message: string}) => void, 
	sk: (data: Buffer) => void, 
) => void 
	= (files, logger, fk, sk) => {
		if (!is_present(files)){
			fk({status: 400, message: "No files were provided"})
			return;
		}

		const docx = files.document as UploadedFile | undefined | null;
		
		if (!is_present(docx)){
			fk({status: 400, message: "No file with key [document] was provided"})
			return;
		}

		const docx_path = docx.tempFilePath;
		// be aware of docx path, this is passed to an exec
		doc_to_pdf(
			docx_path, 
			logger,
			(err) => {
				logger(err);
				fk({status: 500, message: "Libreoffice error"})
			},
			(pdf_path) => 
				get_file_data(pdf_path, 
					(data) =>  sk(data), 
					(err) => {
						logger(err);
						fk({status: 500, message: "Could not read the PDF file from filesystem."})
					}
				)
		)
	}