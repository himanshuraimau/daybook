import { readConfig } from "./config";
import { connect } from "./db";
import { createApp } from "./server";

const config = readConfig();
await connect(config.mongoUri);
Bun.serve({ port: config.port, fetch: createApp(config) });
console.info(`Daybook API listening on port ${config.port}`);
