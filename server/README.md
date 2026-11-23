# Daybook API

Run `bun install`, copy `.env.example` to `.env`, point `MONGODB_URI` at an isolated development database, and run `bun run dev`. Run `bun run check` and `bun test` for verification. `GET /health` reports database readiness.

Sign-up and login are plain email/password; sign-up returns a session immediately. There is no email verification, password reset, or SMTP. Anyone who can reach the API can create an account, so keep the server address private. Keep `.env` out of Git. Set the Android API base URL to an HTTPS endpoint; a local HTTP URL is suitable only for isolated server tests.

## Backup and restore

Before deployment, select the Daybook database by its explicit name and run `mongodump --uri "$MONGODB_URI" --out <private-backup-dir>`. Protect the dump as account and financial data. Test recovery against a **separate empty** database with `mongorestore --uri <test-uri> --nsFrom '<source-db>.*' --nsTo '<test-db>.*' <private-backup-dir>`. Compare document counts and representative expenses, then remove the test database. Never restore into the user's existing MongoDB database without a separately reviewed recovery plan. Exclude authorization headers from reverse-proxy access logs.

## Deploying to Vercel

The API runs on Vercel Functions with the Bun runtime. `api/index.ts` is the single function; `vercel.json` rewrites every path to it, so routes such as `/health` and `/api/expenses` keep their normal URLs. `src/index.ts` is only for local `bun run dev`.

1. Create a Vercel project with **Root Directory** set to `server`. No build command is needed.
2. Add the environment variable `MONGODB_URI` (production and preview), naming the database explicitly, for example `mongodb+srv://USER:PASSWORD@HOST/daybook`. The database must be reachable from the internet (MongoDB Atlas works; allow `0.0.0.0/0` because Vercel's addresses change). Never commit this value.
3. Deploy and open `https://<project>.vercel.app/health`; it should return `{"status":"ok"}`.
4. Build the Android app with `-PDAYBOOK_API_BASE_URL=https://<project>.vercel.app`. Use the production URL, not a preview URL, which may sit behind Vercel login protection.

Login and sign-up attempt limits are stored in MongoDB (`ratelimits` collection, expired automatically) so they hold across function instances.
