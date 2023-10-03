import express from "express";
import { PORT } from "../constants";
import { docx_to_pdf_handler } from "../api/docx_to_pdf_handler";
import { apply_middleware } from "../functions/apply_middleware";
import { make_logger } from "../functions/make_logger";

export const start_express: () => void = () => {
	const server = express();
	const logger = make_logger(`[EXPRESS]`);
	apply_middleware(server);

	server.post('/docx-to-pdf', (req, res) =>
		docx_to_pdf_handler(
			req.files,
			logger,
			function onError({ status, message }) {
				logger(`HTTP: ${status}, Message: ${message}`)
				res
					.status(status)
					.send(message)
			},
			function onSuccess(data) {
				logger(`HTTP: 200`)
				res
					.status(200)
					.setHeader('Content-Type', 'application/pdf')
					.send(data)
			}
		)
	)

	server.listen(PORT, () => {
		logger(`Started listening on port ${PORT}`);
	})
}