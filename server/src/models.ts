import mongoose, { Schema } from "mongoose";

export const CATEGORIES = ["Food", "Transport", "Shopping", "Bills", "Health", "Entertainment", "Other"] as const;

const accountSchema = new Schema({
  email: { type: String, required: true, unique: true, lowercase: true, trim: true },
  passwordHash: { type: String, required: true },
}, { timestamps: true });
export const Account = mongoose.model("Account", accountSchema);

const sessionSchema = new Schema({
  ownerId: { type: Schema.Types.ObjectId, required: true, index: true },
  tokenHash: { type: String, required: true, unique: true },
  expiresAt: { type: Date, required: true },
}, { timestamps: true });
sessionSchema.index({ expiresAt: 1 }, { expireAfterSeconds: 0 });
export const Session = mongoose.model("Session", sessionSchema);

const rateLimitSchema = new Schema({
  key: { type: String, required: true, unique: true },
  count: { type: Number, required: true },
  expiresAt: { type: Date, required: true },
});
rateLimitSchema.index({ expiresAt: 1 }, { expireAfterSeconds: 0 });
export const RateLimit = mongoose.model("RateLimit", rateLimitSchema);

const expenseSchema = new Schema({
  ownerId: { type: Schema.Types.ObjectId, required: true },
  clientId: { type: String, required: true },
  amountMinor: { type: Number, required: true, min: 1, max: 100_000_000_000 },
  currency: { type: String, enum: ["INR"], required: true },
  category: { type: String, enum: CATEGORIES, required: true },
  occurredOn: { type: String, required: true, match: /^\d{4}-\d{2}-\d{2}$/ },
  note: { type: String, default: "", maxlength: 240 },
  version: { type: Number, required: true, default: 1 },
}, { timestamps: true });
expenseSchema.index({ ownerId: 1, clientId: 1 }, { unique: true });
expenseSchema.index({ ownerId: 1, occurredOn: -1, _id: -1 });
export const Expense = mongoose.model("Expense", expenseSchema);

const budgetSchema = new Schema({
  ownerId: { type: Schema.Types.ObjectId, required: true, unique: true },
  incomeMinor: { type: Number, required: true, min: 1, max: 100_000_000_000 },
  payday: { type: Number, required: true, min: 1, max: 31 },
  fixed: [new Schema({
    id: { type: String, required: true, maxlength: 64 },
    name: { type: String, required: true, minlength: 1, maxlength: 40 },
    amountMinor: { type: Number, required: true, min: 1, max: 100_000_000_000 },
    category: { type: String, enum: CATEGORIES, required: true },
    months: { type: Number, min: 1, max: 120 },
    startKey: { type: String, match: /^\d{4}-(0[1-9]|1[0-2])$/ },
  }, { _id: false })],
  savingsMinor: { type: Number, default: 0, min: 0, max: 100_000_000_000 },
  incomes: [new Schema({
    id: { type: String, required: true, maxlength: 64 },
    name: { type: String, required: true, minlength: 1, maxlength: 40 },
    amountMinor: { type: Number, required: true, min: 1, max: 100_000_000_000 },
    date: { type: String, required: true, match: /^\d{4}-\d{2}-\d{2}$/ },
  }, { _id: false })],
  updatedAt: { type: Number, required: true },
});
export const Budget = mongoose.model("Budget", budgetSchema);
