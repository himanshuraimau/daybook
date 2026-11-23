export type Config = {
  port: number;
  mongoUri: string;
};

export function readConfig(env: Record<string, string | undefined> = process.env): Config {
  const port = Number(env.PORT ?? "3000");
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid PORT");
  const mongoUri = env.MONGODB_URI;
  if (!mongoUri || !/^mongodb(\+srv)?:\/\//.test(mongoUri)) throw new Error("MONGODB_URI is required");
  if (env.NODE_ENV === "production" && (!new URL(mongoUri).pathname.slice(1) || new URL(mongoUri).pathname === "/test")) throw new Error("A named non-default database is required");
  return { port, mongoUri };
}
