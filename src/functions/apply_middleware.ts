import { Express } from "express";
import fileUpload from "express-fileupload";
import actuator from "express-actuator";
import bodyParser from "body-parser";
import { TEMPORARY_UPLOAD_PATH } from "../constants";

export const apply_middleware: (server: Express) => void = (server) => {
	server.use(fileUpload({
		useTempFiles: true,
		preserveExtension: 6,
		tempFileDir: TEMPORARY_UPLOAD_PATH,
	}))
	server.use(bodyParser.json());
	server.use(bodyParser.urlencoded({ extended: true }));
	server.use(actuator());
}