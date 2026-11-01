import pg from "pg";
import { createHmac, randomBytes, scrypt as scryptCallback, timingSafeEqual } from "node:crypto";
import { promisify } from "node:util";

const { Pool } = pg;
const scrypt = promisify(scryptCallback);
const DATABASE_URL = process.env.DATABASE_URL?.trim();
const JWT_SECRET = process.env.CLOUD_JWT_SECRET?.trim();

let pool = null;
let schemaReady = null;

function getPool() {
  if (!DATABASE_URL) throw Object.assign(new Error("Cloud database is not configured"), { status: 503 });
  if (!pool) {
    pool = new Pool({
      connectionString: DATABASE_URL,
      ssl: process.env.DATABASE_SSL === "false" ? false : { rejectUnauthorized: false },
      max: 5,
      idleTimeoutMillis: 30000,
      connectionTimeoutMillis: 10000
    });
  }
  return pool;
}

async function ensureSchema() {
  if (!schemaReady) {
    schemaReady = (async () => {
      const db = getPool();
      await db.query(`
        CREATE TABLE IF NOT EXISTS netmusic_users (
          id BIGSERIAL PRIMARY KEY,
          email TEXT NOT NULL UNIQUE,
          display_name TEXT NOT NULL,
          password_hash TEXT NOT NULL,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        );
        CREATE TABLE IF NOT EXISTS netmusic_cloud_state (
          user_id BIGINT PRIMARY KEY REFERENCES netmusic_users(id) ON DELETE CASCADE,
          favorites JSONB NOT NULL DEFAULT '[]'::jsonb,
          history JSONB NOT NULL DEFAULT '[]'::jsonb,
          playlists JSONB NOT NULL DEFAULT '[]'::jsonb,
          downloads JSONB NOT NULL DEFAULT '[]'::jsonb,
          updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        );
      `);
    })().catch(error => {
      schemaReady = null;
      throw error;
    });
  }
  return schemaReady;
}

function encodeToken(userId) {
  if (!JWT_SECRET || JWT_SECRET.length < 32) {
    throw Object.assign(new Error("Cloud authentication is not configured"), { status: 503 });
  }
  const header = Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url");
  const payload = Buffer.from(JSON.stringify({ sub: String(userId), exp: Math.floor(Date.now() / 1000) + 60 * 60 * 24 * 30 })).toString("base64url");
  const data = header + "." + payload;
  const signature = createHmac("sha256", JWT_SECRET).update(data).digest("base64url");
  return data + "." + signature;
}

function decodeToken(token) {
  if (!JWT_SECRET || JWT_SECRET.length < 32) throw Object.assign(new Error("Cloud authentication is not configured"), { status: 503 });
  if (typeof token !== "string" || token.length > 4096) return null;
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  const data = parts[0] + "." + parts[1];
  const expected = createHmac("sha256", JWT_SECRET).update(data).digest();
  let actual;
  try { actual = Buffer.from(parts[2], "base64url"); } catch { return null; }
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) return null;
  try {
    const payload = JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8"));
    if (!payload.sub || !Number.isFinite(payload.exp) || payload.exp <= Math.floor(Date.now() / 1000)) return null;
    return payload;
  } catch { return null; }
}

async function hashPassword(password, salt = randomBytes(16).toString("hex")) {
  const derived = await scrypt(password, salt, 64);
  return salt + ":" + Buffer.from(derived).toString("hex");
}

async function verifyPassword(password, stored) {
  const [salt, expectedHex] = String(stored || "").split(":");
  if (!salt || !expectedHex) return false;
  const actual = Buffer.from(await scrypt(password, salt, 64));
  const expected = Buffer.from(expectedHex, "hex");
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

function normalizeEmail(value) {
  return typeof value === "string" ? value.trim().toLowerCase().slice(0, 254) : "";
}

function validCollection(value, maxItems = 500) {
  return Array.isArray(value) && value.length <= maxItems;
}

function sendError(res, error, fallback = "Cloud service temporarily unavailable") {
  const status = Number.isInteger(error?.status) ? error.status : 500;
  if (status >= 500) console.error("[cloud]", error?.message || fallback);
  res.status(status).json({ error: status === 503 ? "cloud_unavailable" : "request_failed", message: status < 500 ? error.message : fallback });
}

function authRequired(req, res, next) {
  const header = req.headers.authorization || "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  const payload = decodeToken(token);
  if (!payload) return res.status(401).json({ error: "unauthorized", message: "Войди в NET MUSIC Cloud." });
  req.cloudUserId = Number(payload.sub);
  if (!Number.isSafeInteger(req.cloudUserId) || req.cloudUserId <= 0) return res.status(401).json({ error: "unauthorized" });
  next();
}

export function attachCloudRoutes(app) {
  app.get("/api/cloud/status", async (_req, res) => {
    try {
      if (!JWT_SECRET || JWT_SECRET.length < 32) {
        return res.status(503).json({ available: false, service: "NET MUSIC Cloud", missing: ["CLOUD_JWT_SECRET"] });
      }
      await ensureSchema();
      res.json({ available: true, service: "NET MUSIC Cloud", storage: "metadata" });
    } catch (error) {
      res.status(503).json({ available: false, service: "NET MUSIC Cloud", message: "Cloud database is not configured or unavailable." });
    }
  });

  app.post("/api/cloud/register", async (req, res) => {
    try {
      if (!JWT_SECRET || JWT_SECRET.length < 32) return res.status(503).json({ error: "cloud_not_configured", message: "Cloud authentication is not configured on the server." });
      const email = normalizeEmail(req.body?.email);
      const password = typeof req.body?.password === "string" ? req.body.password : "";
      const displayName = typeof req.body?.displayName === "string" ? req.body.displayName.trim().slice(0, 48) : "";
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return res.status(400).json({ error: "invalid_email", message: "Проверь адрес электронной почты." });
      if (password.length < 8 || password.length > 128) return res.status(400).json({ error: "invalid_password", message: "Пароль должен содержать от 8 до 128 символов." });
      if (displayName.length < 2) return res.status(400).json({ error: "invalid_name", message: "Укажи имя длиной хотя бы 2 символа." });
      await ensureSchema();
      const passwordHash = await hashPassword(password);
      const db = getPool();
      const created = await db.query(
        "INSERT INTO netmusic_users(email, display_name, password_hash) VALUES($1, $2, $3) RETURNING id, email, display_name",
        [email, displayName, passwordHash]
      );
      const user = created.rows[0];
      await db.query("INSERT INTO netmusic_cloud_state(user_id) VALUES($1) ON CONFLICT(user_id) DO NOTHING", [user.id]);
      res.status(201).json({ token: encodeToken(user.id), user: { id: String(user.id), email: user.email, displayName: user.display_name } });
    } catch (error) {
      if (error?.code === "23505") return res.status(409).json({ error: "email_exists", message: "Аккаунт с этой почтой уже существует." });
      sendError(res, error);
    }
  });

  app.post("/api/cloud/login", async (req, res) => {
    try {
      if (!JWT_SECRET || JWT_SECRET.length < 32) return res.status(503).json({ error: "cloud_not_configured", message: "Cloud authentication is not configured on the server." });
      const email = normalizeEmail(req.body?.email);
      const password = typeof req.body?.password === "string" ? req.body.password : "";
      if (!email || !password) return res.status(400).json({ error: "missing_credentials", message: "Введи почту и пароль." });
      await ensureSchema();
      const result = await getPool().query("SELECT id, email, display_name, password_hash FROM netmusic_users WHERE email = $1", [email]);
      const user = result.rows[0];
      if (!user || !(await verifyPassword(password, user.password_hash))) {
        return res.status(401).json({ error: "invalid_credentials", message: "Неверная почта или пароль." });
      }
      res.json({ token: encodeToken(user.id), user: { id: String(user.id), email: user.email, displayName: user.display_name } });
    } catch (error) { sendError(res, error); }
  });

  app.get("/api/cloud/me", authRequired, async (req, res) => {
    try {
      await ensureSchema();
      const result = await getPool().query("SELECT id, email, display_name, created_at FROM netmusic_users WHERE id = $1", [req.cloudUserId]);
      const user = result.rows[0];
      if (!user) return res.status(401).json({ error: "unauthorized" });
      res.json({ user: { id: String(user.id), email: user.email, displayName: user.display_name, createdAt: user.created_at } });
    } catch (error) { sendError(res, error); }
  });

  app.get("/api/cloud/sync", authRequired, async (req, res) => {
    try {
      await ensureSchema();
      const result = await getPool().query(
        "SELECT favorites, history, playlists, downloads, updated_at FROM netmusic_cloud_state WHERE user_id = $1",
        [req.cloudUserId]
      );
      const state = result.rows[0] || {};
      res.set("Cache-Control", "no-store");
      res.json({
        favorites: state.favorites || [],
        history: state.history || [],
        playlists: state.playlists || [],
        downloads: state.downloads || [],
        updatedAt: state.updated_at || null
      });
    } catch (error) { sendError(res, error); }
  });

  app.post("/api/cloud/sync", authRequired, async (req, res) => {
    try {
      const body = req.body || {};
      const fields = ["favorites", "history", "playlists", "downloads"];
      for (const field of fields) {
        if (body[field] !== undefined && !validCollection(body[field], field === "history" ? 1000 : 500)) {
          return res.status(400).json({ error: "invalid_sync_data", message: "Некорректные данные облачной библиотеки: " + field });
        }
      }
      await ensureSchema();
      const db = getPool();
      const current = await db.query("SELECT favorites, history, playlists, downloads FROM netmusic_cloud_state WHERE user_id = $1", [req.cloudUserId]);
      const previous = current.rows[0] || {};
      const merge = (before, incoming, limit) => {
        const map = new Map();
        for (const item of [...(Array.isArray(before) ? before : []), ...(Array.isArray(incoming) ? incoming : [])]) {
          if (!item || typeof item !== "object" || Array.isArray(item)) continue;
          const key = String(item.id || item.name || JSON.stringify(item));
          map.set(key, item);
        }
        return [...map.values()].slice(-limit);
      };
      const merged = {
        favorites: merge(previous.favorites, body.favorites, 500),
        history: merge(body.history, previous.history, 1000),
        playlists: merge(previous.playlists, body.playlists, 500),
        downloads: merge(previous.downloads, body.downloads, 500)
      };
      const result = await db.query(
        `INSERT INTO netmusic_cloud_state(user_id, favorites, history, playlists, downloads, updated_at)
         VALUES($1, $2::jsonb, $3::jsonb, $4::jsonb, $5::jsonb, NOW())
         ON CONFLICT(user_id) DO UPDATE SET
           favorites = EXCLUDED.favorites, history = EXCLUDED.history,
           playlists = EXCLUDED.playlists, downloads = EXCLUDED.downloads, updated_at = NOW()
         RETURNING updated_at`,
        [req.cloudUserId, JSON.stringify(merged.favorites), JSON.stringify(merged.history), JSON.stringify(merged.playlists), JSON.stringify(merged.downloads)]
      );
      res.set("Cache-Control", "no-store");
      res.json({ ...merged, saved: true, updatedAt: result.rows[0].updated_at });
    } catch (error) { sendError(res, error); }
  });

  app.put("/api/cloud/sync", authRequired, async (req, res) => {
    try {
      const body = req.body || {};
      const fields = ["favorites", "history", "playlists", "downloads"];
      for (const field of fields) {
        if (body[field] !== undefined && !validCollection(body[field], field === "history" ? 1000 : 500)) {
          return res.status(400).json({ error: "invalid_sync_data", message: "Некорректные данные облачной библиотеки: " + field });
        }
      }
      const values = fields.map(field => JSON.stringify(body[field] ?? []));
      await ensureSchema();
      const result = await getPool().query(
        `INSERT INTO netmusic_cloud_state(user_id, favorites, history, playlists, downloads, updated_at)
         VALUES($1, $2::jsonb, $3::jsonb, $4::jsonb, $5::jsonb, NOW())
         ON CONFLICT(user_id) DO UPDATE SET
           favorites = EXCLUDED.favorites,
           history = EXCLUDED.history,
           playlists = EXCLUDED.playlists,
           downloads = EXCLUDED.downloads,
           updated_at = NOW()
         RETURNING updated_at`,
        [req.cloudUserId, ...values]
      );
      res.json({ saved: true, updatedAt: result.rows[0].updated_at });
    } catch (error) { sendError(res, error); }
  });
}
