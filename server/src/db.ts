import mongoose from "mongoose";

type Cache = { promise?: Promise<typeof mongoose> };
const cache: Cache = ((globalThis as { __daybookDb?: Cache }).__daybookDb ??= {});

/** Connects once per process and reuses the connection across requests (needed on serverless). */
export function connect(uri: string): Promise<typeof mongoose> {
  cache.promise ??= mongoose.connect(uri, { serverSelectionTimeoutMS: 8000, maxPoolSize: 5 })
    .then(async db => { await Promise.all(Object.values(mongoose.models).map(model => model.init())); return db; })
    .catch(error => { cache.promise = undefined; throw error; });
  return cache.promise;
}
