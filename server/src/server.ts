import { createHash, randomBytes } from "node:crypto";
import mongoose from "mongoose";
import type { Config } from "./config";
import { Account, CATEGORIES, Expense, RateLimit, Session } from "./models";

const SESSION_MS = 30 * 24 * 60 * 60 * 1000;
const MAX_BODY = 16_384;
const MIN_PASSWORD = 8;
const WINDOW_MS = 15 * 60_000;
const MAX_ATTEMPTS = 10;
type Dict = Record<string, unknown>;

function json(body: Dict, status = 200): Response {
  return Response.json(body, { status, headers: { "Cache-Control": "no-store" } });
}
function error(code: string, message: string, status: number): Response {
  return json({ error: { code, message } }, status);
}
function hash(value: string): string { return createHash("sha256").update(value).digest("hex"); }
function token(): string { return randomBytes(32).toString("base64url"); }
function email(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.trim().toLowerCase();
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized) && normalized.length <= 254 ? normalized : null;
}
function date(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split("-").map(Number);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  return parsed.getUTCFullYear() === year && parsed.getUTCMonth() + 1 === month && parsed.getUTCDate() === day;
}
function expenseFields(body: Dict): Dict | null {
  if (typeof body.amountMinor !== "number" || !Number.isSafeInteger(body.amountMinor) || body.amountMinor < 1 || body.amountMinor > 100_000_000_000) return null;
  if (body.currency !== "INR" || !CATEGORIES.includes(body.category as typeof CATEGORIES[number]) || !date(body.occurredOn)) return null;
  if (body.note !== undefined && (typeof body.note !== "string" || body.note.length > 240)) return null;
  return { amountMinor: body.amountMinor, currency: "INR", category: body.category, occurredOn: body.occurredOn, note: body.note ?? "" };
}
async function readBody(req: Request): Promise<string | null> {
  if (Number(req.headers.get("content-length") ?? "0") > MAX_BODY) return null;
  if (!req.body) return null;
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_BODY) { await reader.cancel(); return null; }
    chunks.push(value);
  }
  return Buffer.concat(chunks).toString("utf8");
}
async function bodyOf(req: Request): Promise<Dict | null> {
  try {
    const raw = await readBody(req);
    if (raw === null) return null;
    const parsed: unknown = JSON.parse(raw);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed as Dict : null;
  } catch { return null; }
}
function expenseDto(expense: { id: string; clientId: string; amountMinor: number; currency: string; category: string; occurredOn: string; note: string; version: number; createdAt: Date; updatedAt: Date }): Dict {
  return { id: expense.id, clientId: expense.clientId, amountMinor: expense.amountMinor, currency: expense.currency,
    category: expense.category, occurredOn: expense.occurredOn, note: expense.note, version: expense.version,
    createdAt: expense.createdAt, updatedAt: expense.updatedAt };
}
async function ownerOf(req: Request): Promise<string | null> {
  const match = /^Bearer ([A-Za-z0-9_-]{40,})$/.exec(req.headers.get("authorization") ?? "");
  if (!match) return null;
  const session = await Session.findOne({ tokenHash: hash(match[1]), expiresAt: { $gt: new Date() } });
  return session?.ownerId.toString() ?? null;
}
async function startSession(account: { _id: mongoose.Types.ObjectId; id: string; email: string }): Promise<Dict> {
  const raw = token();
  const expiresAt = new Date(Date.now() + SESSION_MS);
  await Session.create({ ownerId: account._id, tokenHash: hash(raw), expiresAt });
  return { token: raw, expiresAt: expiresAt.toISOString(), account: { id: account.id, email: account.email } };
}
/** Counts an attempt for `key` in MongoDB so the limit holds across serverless instances. */
async function limited(key: string): Promise<boolean> {
  const now = new Date();
  const fresh = new Date(now.getTime() + WINDOW_MS);
  const live = { $gt: ["$expiresAt", now] };
  const doc = await RateLimit.findOneAndUpdate({ key }, [{ $set: {
    count: { $cond: [live, { $add: ["$count", 1] }, 1] },
    expiresAt: { $cond: [live, "$expiresAt", fresh] },
  } }], { upsert: true, returnDocument: "after", updatePipeline: true });
  return doc.count > MAX_ATTEMPTS;
}

export function createApp(config: Config): (req: Request) => Promise<Response> {
  return async req => {
    try {
      const url = new URL(req.url);
      const path = url.pathname;
      if (path === "/health" && req.method === "GET") {
        return json({ status: mongoose.connection.readyState === 1 ? "ok" : "unavailable" }, mongoose.connection.readyState === 1 ? 200 : 503);
      }
      if (path === "/api/auth/signup" && req.method === "POST") {
        const body = await bodyOf(req);
        const address = email(body?.email);
        if (!address || typeof body?.password !== "string" || body.password.length < MIN_PASSWORD || body.password.length > 128) return error("invalid_input", `Enter a valid email and a password of at least ${MIN_PASSWORD} characters`, 400);
        if (await limited(`signup:${address}`)) return error("rate_limited", "Try again later", 429);
        if (await Account.exists({ email: address })) return error("email_taken", "This email already has an account. Log in instead.", 409);
        const passwordHash = await Bun.password.hash(body.password, { algorithm: "argon2id" });
        let account;
        try { account = await Account.create({ email: address, passwordHash }); }
        catch (cause) {
          if ((cause as { code?: number }).code === 11000) return error("email_taken", "This email already has an account. Log in instead.", 409);
          throw cause;
        }
        return json(await startSession(account), 201);
      }
      if (path === "/api/auth/login" && req.method === "POST") {
        const body = await bodyOf(req);
        const address = email(body?.email);
        if (!address || typeof body?.password !== "string") return error("invalid_input", "Enter an email and password", 400);
        if (await limited(`login:${address}`)) return error("rate_limited", "Try again later", 429);
        const account = await Account.findOne({ email: address });
        const valid = account && await Bun.password.verify(body.password, account.passwordHash);
        if (!valid || !account) return error("invalid_credentials", "Email or password is incorrect", 401);
        await RateLimit.deleteOne({ key: `login:${address}` });
        return json(await startSession(account));
      }
      if (!path.startsWith("/api/")) return error("not_found", "Not found", 404);
      const ownerId = await ownerOf(req);
      if (!ownerId) return error("unauthorized", "Log in again", 401);
      if (path === "/api/auth/me" && req.method === "GET") {
        const account = await Account.findById(ownerId);
        return account ? json({ account: { id: account.id, email: account.email } }) : error("unauthorized", "Log in again", 401);
      }
      if (path === "/api/auth/logout" && req.method === "POST") {
        const raw = req.headers.get("authorization")!.slice(7);
        await Session.deleteOne({ ownerId, tokenHash: hash(raw) });
        return json({ message: "Signed out" });
      }
      if (path === "/api/expenses" && req.method === "POST") {
        const body = await bodyOf(req);
        const fields = body && expenseFields(body);
        if (!fields || typeof body?.clientId !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(body.clientId)) return error("invalid_input", "Check expense fields", 400);
        const existing = await Expense.findOne({ ownerId, clientId: body.clientId });
        if (existing) return sameExpense(existing, fields) ? json({ expense: expenseDto(existing) }) : error("conflict", "Client ID already belongs to another expense", 409);
        try {
          const created = await Expense.create({ ...fields, ownerId, clientId: body.clientId });
          return json({ expense: expenseDto(created) }, 201);
        } catch (cause) {
          if ((cause as { code?: number }).code === 11000) {
            const found = await Expense.findOne({ ownerId, clientId: body.clientId });
            if (found) return sameExpense(found, fields) ? json({ expense: expenseDto(found) }) : error("conflict", "Client ID already belongs to another expense", 409);
          }
          throw cause;
        }
      }
      if (path === "/api/expenses" && req.method === "GET") {
        const from = url.searchParams.get("from"), to = url.searchParams.get("to"), cursor = url.searchParams.get("cursor");
        if (!date(from) || !date(to) || from > to) return error("invalid_input", "Enter a valid date range", 400);
        const query: Dict = { ownerId, occurredOn: { $gte: from, $lte: to } };
        if (cursor) {
          try {
            const parsed = JSON.parse(Buffer.from(cursor, "base64url").toString()) as { day: unknown; id: unknown };
            if (!date(parsed.day) || typeof parsed.id !== "string" || !mongoose.isValidObjectId(parsed.id)) throw new Error();
            query.$or = [{ occurredOn: { $lt: parsed.day } }, { occurredOn: parsed.day, _id: { $lt: parsed.id } }];
          } catch { return error("invalid_input", "Invalid cursor", 400); }
        }
        const list = await Expense.find(query).sort({ occurredOn: -1, _id: -1 }).limit(51);
        const page = list.slice(0, 50);
        const last = page.at(-1);
        return json({ expenses: page.map(expenseDto), nextCursor: list.length > 50 && last ? Buffer.from(JSON.stringify({ day: last.occurredOn, id: last.id })).toString("base64url") : null });
      }
      const expenseMatch = /^\/api\/expenses\/([0-9a-f]{24})$/.exec(path);
      if (expenseMatch && req.method === "GET") {
        const found = await Expense.findOne({ _id: expenseMatch[1], ownerId });
        return found ? json({ expense: expenseDto(found) }) : error("not_found", "Expense not found", 404);
      }
      if (expenseMatch && req.method === "PATCH") {
        const body = await bodyOf(req);
        const fields = body && expenseFields(body);
        if (!fields || !Number.isSafeInteger(body?.version) || (body!.version as number) < 1) return error("invalid_input", "Check expense fields and version", 400);
        const updated = await Expense.findOneAndUpdate({ _id: expenseMatch[1], ownerId, version: body!.version as number }, { $set: fields, $inc: { version: 1 } }, { returnDocument: "after", runValidators: true });
        if (updated) return json({ expense: expenseDto(updated) });
        return (await Expense.exists({ _id: expenseMatch[1], ownerId })) ? error("conflict", "Expense changed elsewhere. Refresh and try again.", 409) : error("not_found", "Expense not found", 404);
      }
      if (expenseMatch && req.method === "DELETE") {
        const version = Number(url.searchParams.get("version"));
        if (!Number.isSafeInteger(version) || version < 1) return error("invalid_input", "A version is required", 400);
        const deleted = await Expense.findOneAndDelete({ _id: expenseMatch[1], ownerId, version });
        if (deleted) return json({ message: "Expense deleted" });
        return (await Expense.exists({ _id: expenseMatch[1], ownerId })) ? error("conflict", "Expense changed elsewhere. Refresh and try again.", 409) : error("not_found", "Expense not found", 404);
      }
      if (path === "/api/summary" && req.method === "GET") {
        const month = url.searchParams.get("month"), today = url.searchParams.get("today");
        if (!month || !/^\d{4}-\d{2}$/.test(month) || !date(`${month}-01`) || !date(today)) return error("invalid_input", "Enter a valid month and local today", 400);
        const expenses = await Expense.find({ ownerId, occurredOn: { $gte: `${month}-01`, $lt: month === "9999-12" ? "9999-12-32" : nextMonth(month) } });
        let monthMinor = 0;
        const categories: Record<string, number> = {};
        for (const item of expenses) {
          monthMinor += item.amountMinor;
          categories[item.category] = (categories[item.category] ?? 0) + item.amountMinor;
        }
        const todayExpenses = today.startsWith(month) ? expenses.filter(item => item.occurredOn === today) : await Expense.find({ ownerId, occurredOn: today });
        const todayMinor = todayExpenses.reduce((sum, item) => sum + item.amountMinor, 0);
        if (!Number.isSafeInteger(monthMinor) || !Number.isSafeInteger(todayMinor)) return error("total_too_large", "Total exceeds supported range", 422);
        return json({ month, today, monthMinor, todayMinor, categories });
      }
      return error("not_found", "Not found", 404);
    } catch (cause) {
      if (cause instanceof mongoose.Error.ValidationError) return error("invalid_input", "Check the submitted values", 400);
      console.error("API request failed", cause instanceof Error ? cause.name : "unknown");
      return error("server_error", "Please try again", 500);
    }
  };
}

function nextMonth(month: string): string {
  const [year, number] = month.split("-").map(Number);
  return number === 12 ? `${String(year + 1).padStart(4, "0")}-01-01` : `${String(year).padStart(4, "0")}-${String(number + 1).padStart(2, "0")}-01`;
}

function sameExpense(expense: { amountMinor: number; currency: string; category: string; occurredOn: string; note: string }, fields: Dict): boolean {
  return expense.amountMinor === fields.amountMinor && expense.currency === fields.currency &&
    expense.category === fields.category && expense.occurredOn === fields.occurredOn && expense.note === fields.note;
}
