import { afterAll, beforeAll, expect, test } from "bun:test";
import mongoose from "mongoose";
import { readConfig } from "../src/config";
import { Account, Budget, Expense, RateLimit, Session } from "../src/models";
import { createApp } from "../src/server";

const uri = process.env.MONGODB_URI_TEST;
let app: ReturnType<typeof createApp>;
let a = "", b = "";

beforeAll(async () => {
  if (!uri) return;
  await mongoose.connect(uri);
  await Promise.all(Object.values(mongoose.models).map(model => model.init()));
  await mongoose.connection.dropDatabase();
  app = createApp(readConfig({ MONGODB_URI: uri }));
  const first = await Account.create({ email: "a@example.test", passwordHash: await Bun.password.hash("a very long password", { algorithm: "argon2id" }) });
  const second = await Account.create({ email: "b@example.test", passwordHash: await Bun.password.hash("another long password", { algorithm: "argon2id" }) });
  a = first.id; b = second.id;
});
afterAll(async () => { if (uri) { await mongoose.connection.dropDatabase(); await mongoose.disconnect(); } });

async function request(path: string, method = "GET", token?: string, body?: unknown) {
  const response = await app(new Request(`http://localhost${path}`, { method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  }));
  return { status: response.status, body: await response.json() as any };
}
async function login(address: string, password: string) {
  const result = await request("/api/auth/login", "POST", undefined, { email: address, password });
  expect(result.status).toBe(200);
  return result.body.token as string;
}

test("config rejects missing database", () => {
  expect(() => readConfig({})).toThrow();
  expect(() => readConfig({ MONGODB_URI: "mongodb://localhost", NODE_ENV: "production" })).toThrow();
  expect(readConfig({ MONGODB_URI: "mongodb://localhost/test" }).port).toBe(3000);
});

test.skipIf(!uri)("sign-up logs in immediately, rejects duplicates and short passwords, and logins are rate limited", async () => {
  expect((await request("/api/auth/signup", "POST", undefined, { email: "new@example.test", password: "short" })).status).toBe(400);
  expect((await request("/api/auth/signup", "POST", undefined, { email: "not-an-email", password: "long enough password" })).status).toBe(400);
  const created = await request("/api/auth/signup", "POST", undefined, { email: "New@Example.test", password: "simple pass" });
  expect(created.status).toBe(201);
  expect(created.body.account.email).toBe("new@example.test");
  expect((await request("/api/auth/me", "GET", created.body.token)).body.account.id).toBe(created.body.account.id);
  const stored = await Account.findOne({ email: "new@example.test" });
  expect(stored?.passwordHash.startsWith("$argon2id$")).toBe(true);
  expect((await request("/api/auth/signup", "POST", undefined, { email: "new@example.test", password: "another pass" })).status).toBe(409);
  expect((await login("new@example.test", "simple pass")).length).toBeGreaterThan(40);
  expect((await request("/api/auth/login", "POST", undefined, { email: "new@example.test", password: "wrong password" })).status).toBe(401);
  expect((await request("/api/auth/password-reset/request", "POST", undefined, { email: "new@example.test" })).status).toBe(401);
  let status = 0;
  for (let attempt = 0; attempt < 11; attempt++) status = (await request("/api/auth/login", "POST", undefined, { email: "unknown@example.test", password: "incorrect password" })).status;
  expect(status).toBe(429);
});

test.skipIf(!uri)("login, ownership, dates, idempotency, edit, summary and logout", async () => {
  const first = await login("a@example.test", "a very long password");
  const second = await login("b@example.test", "another long password");
  expect((await request("/api/auth/me", "GET", first)).body.account.id).toBe(a);
  expect((await request("/api/auth/me", "GET", second)).body.account.id).toBe(b);
  const payload = { clientId: "c267f147-74ae-4404-a756-d970fb6f1fe0", amountMinor: 1999, currency: "INR", category: "Food", occurredOn: "2026-11-30", note: "Lunch" };
  expect((await request("/api/expenses", "POST", first, { ...payload, occurredOn: "2026-11-31" })).status).toBe(400);
  const created = await request("/api/expenses", "POST", first, payload);
  expect(created.status).toBe(201);
  const id = created.body.expense.id as string;
  expect((await request(`/api/expenses/${id}`, "GET", first)).body.expense.id).toBe(id);
  expect((await request(`/api/expenses/${id}`, "GET", second)).status).toBe(404);
  expect((await request("/api/expenses", "POST", first, payload)).body.expense.id).toBe(id);
  expect((await request("/api/expenses", "POST", first, { ...payload, amountMinor: 2 })).status).toBe(409);
  expect(await Expense.countDocuments({ ownerId: a })).toBe(1);
  expect((await request("/api/expenses?from=2026-11-01&to=2026-11-30", "GET", second)).body.expenses).toEqual([]);
  expect((await request(`/api/expenses/${id}`, "PATCH", second, { ...payload, version: 1 })).status).toBe(404);
  const november = await request("/api/summary?month=2026-11&today=2026-11-30", "GET", first);
  expect(november.body).toMatchObject({ todayMinor: 1999, monthMinor: 1999, categories: { Food: 1999 } });
  const changed = await request(`/api/expenses/${id}`, "PATCH", first, { ...payload, occurredOn: "2026-12-01", amountMinor: 2501, version: 1 });
  expect(changed.status).toBe(200);
  expect(changed.body.expense.version).toBe(2);
  expect((await request(`/api/expenses/${id}`, "PATCH", first, { ...payload, version: 1 })).status).toBe(409);
  expect((await request("/api/summary?month=2026-11&today=2026-11-30", "GET", first)).body.monthMinor).toBe(0);
  expect((await request("/api/summary?month=2026-12&today=2026-12-01", "GET", first)).body.todayMinor).toBe(2501);
  expect((await request("/api/summary?month=2026-11&today=2026-12-01", "GET", first)).body.todayMinor).toBe(2501);
  expect((await request(`/api/expenses/${id}?version=1`, "DELETE", first)).status).toBe(409);
  expect((await request(`/api/expenses/${id}?version=2`, "DELETE", first)).status).toBe(200);
  expect((await request("/api/auth/logout", "POST", first)).status).toBe(200);
  expect((await request("/api/auth/me", "GET", first)).status).toBe(401);
  await Session.updateMany({ ownerId: b }, { $set: { expiresAt: new Date(0) } });
  expect((await request("/api/auth/me", "GET", second)).status).toBe(401);
  expect((await request("/api/expenses", "POST", second, { ...payload, clientId: "c267f147-74ae-4404-a756-d970fb6f1fe1" })).status).toBe(401);
  expect(await Expense.countDocuments({ ownerId: b })).toBe(0);
});

test.skipIf(!uri)("history paginates without skipping equal-date expenses", async () => {
  const first = await login("a@example.test", "a very long password");
  for (let index = 0; index < 52; index++) {
    const result = await request("/api/expenses", "POST", first, {
      clientId: crypto.randomUUID(), amountMinor: 1, currency: "INR", category: "Other", occurredOn: "2026-10-01", note: String(index),
    });
    expect(result.status).toBe(201);
  }
  const page1 = await request("/api/expenses?from=2026-10-01&to=2026-10-31", "GET", first);
  expect(page1.body.expenses).toHaveLength(50);
  expect(page1.body.nextCursor).toBeTruthy();
  const page2 = await request(`/api/expenses?from=2026-10-01&to=2026-10-31&cursor=${page1.body.nextCursor}`, "GET", first);
  expect(page2.body.expenses).toHaveLength(2);
  expect(page2.body.nextCursor).toBeNull();
  expect(new Set([...page1.body.expenses, ...page2.body.expenses].map((entry: any) => entry.id)).size).toBe(52);
});

test.skipIf(!uri)("login limit is stored in MongoDB, resets on success, and expires with its window", async () => {
  for (let attempt = 0; attempt < 3; attempt++) await request("/api/auth/login", "POST", undefined, { email: "a@example.test", password: "wrong password" });
  expect((await RateLimit.findOne({ key: "login:a@example.test" }))?.count).toBe(3);
  await login("a@example.test", "a very long password");
  expect(await RateLimit.findOne({ key: "login:a@example.test" })).toBeNull();
  await RateLimit.create({ key: "login:old@example.test", count: 50, expiresAt: new Date(Date.now() - 1000) });
  expect((await request("/api/auth/login", "POST", undefined, { email: "old@example.test", password: "wrong password" })).status).toBe(401);
  expect((await RateLimit.findOne({ key: "login:old@example.test" }))?.count).toBe(1);
});

test.skipIf(!uri)("Vercel entry point serves the API on the original path and fails closed without config", async () => {
  const { default: vercel } = await import("../api/index");
  const saved = process.env.MONGODB_URI;
  delete process.env.MONGODB_URI;
  expect((await vercel.fetch(new Request("http://localhost/health"))).status).toBe(503);
  process.env.MONGODB_URI = uri;
  const health = await vercel.fetch(new Request("http://localhost/health"));
  expect(health.status).toBe(200);
  expect(await health.json()).toEqual({ status: "ok" });
  expect((await vercel.fetch(new Request("http://localhost/api/expenses"))).status).toBe(401);
  process.env.MONGODB_URI = saved;
});

test.skipIf(!uri)("budget profile: validation, account scope, last write wins, newer copy wins on conflict", async () => {
  const first = await login("a@example.test", "a very long password");
  const second = await login("b@example.test", "another long password");
  expect((await request("/api/budget", "GET", first)).body.budget).toBeNull();
  expect((await request("/api/budget", "GET")).status).toBe(401);
  const now = Date.now();
  const rent = { id: "rent-1", name: "Rent", amountMinor: 1_000_000, category: "Bills" };
  const good = { incomeMinor: 5_000_000, payday: 27, fixed: [rent], updatedAt: now };
  for (const bad of [
    { ...good, incomeMinor: 0 }, { ...good, incomeMinor: 10.5 }, { ...good, payday: 0 }, { ...good, payday: 32 },
    { ...good, fixed: [{ ...rent, amountMinor: -1 }] }, { ...good, fixed: [{ ...rent, category: "Nope" }] },
    { ...good, fixed: [{ ...rent, name: "  " }] }, { ...good, fixed: [rent, rent] }, { ...good, updatedAt: now + 3 * 86_400_000 },
    { ...good, fixed: Array.from({ length: 31 }, (_, i) => ({ ...rent, id: `f${i}` })) },
  ]) expect((await request("/api/budget", "PUT", first, bad)).status).toBe(400);
  const saved = await request("/api/budget", "PUT", first, good);
  expect(saved.status).toBe(200);
  expect(saved.body.budget).toEqual(good);
  expect((await request("/api/budget", "GET", second)).body.budget).toBeNull();
  const newer = { ...good, incomeMinor: 6_000_000, fixed: [], updatedAt: now + 1000 };
  expect((await request("/api/budget", "PUT", first, newer)).status).toBe(200);
  const stale = await request("/api/budget", "PUT", first, { ...good, updatedAt: now + 500 });
  expect(stale.status).toBe(409);
  expect(stale.body.budget.incomeMinor).toBe(6_000_000);
  expect((await request("/api/budget", "GET", first)).body.budget).toEqual(newer);
  expect(await Budget.countDocuments({ ownerId: a })).toBe(1);
});

test.skipIf(!uri)("budget EMIs: months and start cycle are validated, stored and returned", async () => {
  const token = await login("a@example.test", "a very long password");
  const base = Date.now() + 10_000;
  const emi = { id: "phone", name: "Phone EMI", amountMinor: 500_000, category: "Shopping", months: 12, startKey: "2026-11" };
  const body = { incomeMinor: 5_000_000, payday: 27, fixed: [{ id: "rent", name: "Rent", amountMinor: 1_000_000, category: "Bills" }, emi], updatedAt: base };
  for (const bad of [
    { ...emi, months: 0 }, { ...emi, months: 121 }, { ...emi, months: 2.5 }, { ...emi, startKey: "2026-13" }, { ...emi, startKey: "Nov 2026" },
    { ...emi, startKey: undefined }, { ...emi, months: undefined },
  ]) expect((await request("/api/budget", "PUT", token, { ...body, updatedAt: base + 1, fixed: [bad] })).status).toBe(400);
  const saved = await request("/api/budget", "PUT", token, body);
  expect(saved.status).toBe(200);
  expect(saved.body.budget.fixed).toEqual(body.fixed);          // plain fixed spend has no EMI fields, the EMI keeps both
  expect((await request("/api/budget", "GET", token)).body.budget.fixed[1]).toEqual(emi);
});
