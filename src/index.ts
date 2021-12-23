import express, {Request, Response, Express} from "express";
import fileUpload, { UploadedFile } from "express-fileupload";
import bodyParser from "body-parser";
import tmp from "tmp";
import { exec } from "child_process";
import { readFile } from "fs";

const PORT = 9999;
const TEMPORARY_PDF_PATH = "/tmp/generated_pdfs";
const TEMPORARY_UPLOAD_PATH = '/tmp/uploaded_docx';

// This project inspired by this response: https://stackoverflow.com/a/30465397

const isPresent = <R>(x: R): x is NonNullable<R> => x !== null && x !== undefined;

const docxToPdfHandler: (req: Request, res: Response) => void = (req, res) => {
	if (!isPresent(req.files)){
		res.status(400).send("No files were provided")
		return;
	}
	const docx = req.files.document as UploadedFile | undefined | null;
	if (!isPresent(docx)){
		res.status(400).send("No file with key [document] was provided")
		return;
	}

	const docxPath = docx.tempFilePath;
	console.log(docxPath);
	
	const docxName = docxPath.split('/')[3];
	tmp.file((err, path) => {
		if (err) {
			console.error(err);
			res.status(500).send('Cannot create temporary file to store the pdf at.')
			return;
		} 
		// lol, this can be RCE, i guess?
		exec(`
			libreoffice \
				--headless \
				--convert-to pdf:writer_pdf_Export \
				--outdir ${TEMPORARY_PDF_PATH} \
				${docxPath}
		`, (err) => {
			if (err) {
				console.error(err);
				res.status(500).send("Libreoffice error")
				return;
			}
			const pdfPath = `${TEMPORARY_PDF_PATH}/${docxName}.pdf`
			console.log(pdfPath);
			
			readFile(pdfPath, (err, data) => {
				if (err){
					res.status(500).send("Could not read the PDF file from filesystem.")
					return;
				}
				res.status(200)
				.setHeader('Content-Type', 'application/pdf')
				.send(data);
			})
		})
	})
	
}

const applyMiddleware: (server: Express) => void = (server) => {
	server.use(fileUpload({
		useTempFiles: true,
		preserveExtension: 6,
		tempFileDir : TEMPORARY_UPLOAD_PATH
	}))
	server.use(bodyParser.json());
	server.use(bodyParser.urlencoded({extended: true}));
}

const start: () => void = () => {
	const server = express();
	applyMiddleware(server);

	server.post('/docx-to-pdf', docxToPdfHandler)
	server.listen(PORT, () => {
		console.log(`Started listening on port ${PORT}`);
	})

}

start()