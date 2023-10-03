import { ChildProcess, spawn } from "child_process";

export const start_unoserver: () => Promise<ChildProcess> = () => {
    return new Promise((res, rej) => {
        const child = spawn('python3', ['-m', 'unoserver.server', '--daemon']);

        child.stdout.on('data', (data) => {
            console.log(`[UNOSERVER] ${data}`);
        });

        child.stderr.on('data', (data) => {
            console.error(`[UNOSERVER] ${data}`);
        });

        child.on('error', (error) => {
            console.error(`Error starting unoserver: ${error.message}`);
            rej(error)
        });

        child.on('spawn', () => {
            console.log('Started unoserver')
            return res(child)
        })
    })
}