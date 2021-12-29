// This project inspired by this response: https://stackoverflow.com/a/30465397

import { start_cleanup_automation_bot } from "./services/cleanup_automation_bot";
import { start_express } from "./services/express_app";
import './services/env_variables';

start_express();
start_cleanup_automation_bot();