import { Request } from "express";
import { UploadedFile } from "express-fileupload";
import { doc_to_pdf_cli } from "../functions/doc_to_pdf_cli";
import { get_file_data } from "../functions/get_file_data";
import { is_present } from "../functions/is_present";
import { logger_function_type } from "../functions/make_logger";
import { doc_to_pdf_uno } from "../functions/doc_to_pdf_uno";

export const docx_to_pdf_handler: (
	mode: 'cli' | 'uno',
	files: Request['files'],
	logger: logger_function_type,
	fk: (args: { status: 400 | 500, message: string }) => void,
	sk: (data: Buffer) => void,
) => void
	= (mode, files, logger, fk, sk) => {
		if (!is_present(files)) {
			fk({ status: 400, message: "No files were provided" })
			return;
		}

		const docx = files.document as UploadedFile | undefined | null;

		if (!is_present(docx)) {
			fk({ status: 400, message: "No file with key [document] was provided" })
			return;
		}

		const docx_path = docx.tempFilePath;
		
		switch (mode) {
			case 'cli': {
				// be aware of docx path, this is passed to an exec
				doc_to_pdf_cli(
					docx_path,
					logger,
					(err) => {
						logger(err);
						fk({ status: 500, message: "Libreoffice error" })
					},
					(pdf_path) =>
						get_file_data(pdf_path,
							(data) => sk(data),
							(err) => {
								logger(err);
								fk({ status: 500, message: "Could not read the PDF file from filesystem." })
							}
						)
				)
				return;
			}
			case 'uno': {
				doc_to_pdf_uno(
					docx_path,
					logger,
					(err) => {
						logger(err);
						fk({ status: 500, message: "unoserver error" })
					},
					(pdf_path) =>
						get_file_data(pdf_path,
							(data) => sk(data),
							(err) => {
								logger(err);
								fk({ status: 500, message: "Could not read the PDF file from filesystem." })
							}
						)
				)
				return;
			}

			default: {
				const x: never = mode;
				throw x;
			}
		}
		
	}