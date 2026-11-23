import { readConfig } from "../src/config";
import { connect } from "../src/db";
import { createApp } from "../src/server";

let handler: ((req: Request) => Promise<Response>) | undefined;
let uri = "";

export default {
  async fetch(req: Request): Promise<Response> {
    try {
      if (!handler) { const config = readConfig(); uri = config.mongoUri; handler = createApp(config); }
      await connect(uri);
      return await handler(req);
    } catch (error) {
      console.error("Daybook API unavailable:", error instanceof Error ? error.message : "unknown error");
      return Response.json({ error: { code: "unavailable", message: "Service unavailable" } }, { status: 503, headers: { "Cache-Control": "no-store" } });
    }
  },
};
