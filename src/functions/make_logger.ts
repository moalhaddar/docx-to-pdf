
export type logger_function_type = (...args: any[]) => void;

export const make_logger: (...prefix: any[]) => logger_function_type = (...prefix) =>
	(...args) => console.log(...prefix, ...args);