import express, {Request, Express} from "express";
import fileUpload, { UploadedFile } from "express-fileupload";
import bodyParser from "body-parser";
import { PORT, TEMPORARY_UPLOAD_PATH } from "./constants";
import { doc_to_pdf } from "./functions/doc_to_pdf";
import { get_file } from "./functions/get_file";
import { is_present } from "./functions/is_present";
import { cleanup_files } from "./functions/delete_file_if_exist";

// This project inspired by this response: https://stackoverflow.com/a/30465397

const docx_to_pdf_handler: (
		sk: (data: Buffer) => void, 
		fk: (args: {status: 400 | 500, message: string}) => void, 
		files: Request['files']
	) => void 
	= (sk, fk, files) => {
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
		doc_to_pdf(docx_path, 
			(pdf_path) => 
				get_file(pdf_path, 
					(data) => 
						cleanup_files(
							[docx_path, pdf_path],
							() => sk(data),
							() => fk({status: 500, message: "Could not clean up files"})
						), 
					(err) => {
						console.error(err);
						fk({status: 500, message: "Could not read the PDF file from filesystem."})
					}
				)
			, 
			(err) => {
				console.error(err);
				fk({status: 500, message: "Libreoffice error"})
			}
		)
}

const apply_middleware: (server: Express) => void = (server) => {
	server.use(fileUpload({
		useTempFiles: true,
		preserveExtension: 6,
		tempFileDir : TEMPORARY_UPLOAD_PATH,
	}))
	server.use(bodyParser.json());
	server.use(bodyParser.urlencoded({extended: true}));
}

const start: () => void = () => {
	const server = express();
	apply_middleware(server);

	server.post('/docx-to-pdf', (req, res) => 
		docx_to_pdf_handler(
			(data) => {
				res
				.status(200)
				.setHeader('Content-Type', 'application/pdf')
				.send(data)
			},
			({status, message}) => {
				res
				.status(status)
				.send(message)
			},
			req.files
		)
	)

	server.listen(PORT, () => {
		console.log(`Started listening on port ${PORT}`);
	})

	// TODO: Cleaning up timeout
}

start();