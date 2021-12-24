import { exec, ExecException } from "child_process";
import { TEMPORARY_PDF_PATH } from "../constants";

export const doc_to_pdf: (docxPath: string, sk: (pdfPath: string) => void, fk: (err: ExecException) => void) => void = (docxPath, sk, fk) => 
	// docx path is a temporary file, not a user input
	exec(`
		libreoffice \
			--headless \
			--convert-to pdf:writer_pdf_Export \
			--outdir ${TEMPORARY_PDF_PATH} \
			"${docxPath}" 
		`, (err) => {
			if (err) {
				fk(err);
				return;
			}
			const docxName = docxPath.split('/')[3];
			console.log(`Converted ${docxName} successfully`);
			const pdfPath = `${TEMPORARY_PDF_PATH}/${docxName}.pdf`
			sk(pdfPath);
		}
	)