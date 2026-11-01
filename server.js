import express from "express";
import { randomBytes, createHmac, timingSafeEqual } from "node:crypto";

const app = express();
const PORT = Number(process.env.PORT || 3000);
const SPOTIFY_CLIENT_ID = process.env.SPOTIFY_CLIENT_ID?.trim();
const SPOTIFY_CLIENT_SECRET = process.env.SPOTIFY_CLIENT_SECRET?.trim();
const SPOTIFY_REDIRECT_URI =
  process.env.SPOTIFY_REDIRECT_URI?.trim() ||
  "https://net-music.onrender.com/auth/spotify/callback";
const APP_REDIRECT_URI =
  process.env.APP_REDIRECT_URI?.trim() || "netmusic://spotify/callback";

app.disable("x-powered-by");
app.use(express.json({ limit: "16kb" }));

const loginTickets = new Map();
const STATE_TTL_MS = 10 * 60 * 1000;
const TICKET_TTL_MS = 60 * 1000;

function randomToken(bytes = 32) {
  return randomBytes(bytes).toString("base64url");
}

function prune(map) {
  const now = Date.now();
  for (const [key, value] of map) {
    if (value.expiresAt <= now) map.delete(key);
  }
}

function createOAuthState() {
  const timestamp = Date.now().toString();
  const nonce = randomToken(24);
  const payload = `${timestamp}.${nonce}`;
  const signature = createHmac("sha256", SPOTIFY_CLIENT_SECRET)
    .update(payload)
    .digest("base64url");
  return `${payload}.${signature}`;
}

function verifyOAuthState(state) {
  if (typeof state !== "string") return false;
  const parts = state.split(".");
  if (parts.length !== 3) return false;

  const [timestampText, nonce, suppliedSignature] = parts;
  if (!/^\\d+$/.test(timestampText) || !nonce || !suppliedSignature) return false;

  const timestamp = Number(timestampText);
  const age = Date.now() - timestamp;
  if (!Number.isSafeInteger(timestamp) || age < 0 || age > STATE_TTL_MS) return false;

  const payload = `${timestampText}.${nonce}`;
  const expected = createHmac("sha256", SPOTIFY_CLIENT_SECRET)
    .update(payload)
    .digest();
  let supplied;
  try {
    supplied = Buffer.from(suppliedSignature, "base64url");
  } catch {
    return false;
  }
  return supplied.length === expected.length && timingSafeEqual(supplied, expected);
}

function requireSpotifyConfig(res) {
  if (!SPOTIFY_CLIENT_ID || !SPOTIFY_CLIENT_SECRET) {
    res.status(503).json({
      error: "spotify_not_configured",
      message: "Add SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET in Render Environment."
    });
    return false;
  }
  return true;
}

app.get("/", (_req, res) => {
  res.type("text").send("NET MUSIC API is running");
});

app.get("/health", (_req, res) => {
  res.json({ ok: true, service: "net-music-api" });
});

// Starts Spotify OAuth. State is generated and checked on the server.
app.get("/auth/spotify", (req, res) => {
  if (!requireSpotifyConfig(res)) return;

  const state = createOAuthState();

  const params = new URLSearchParams({
    client_id: SPOTIFY_CLIENT_ID,
    response_type: "code",
    redirect_uri: SPOTIFY_REDIRECT_URI,
    state,
    scope: [
      "user-read-private",
      "user-read-email",
      "user-library-read",
      "user-read-playback-state"
    ].join(" ")
  });

  res.redirect(302, `https://accounts.spotify.com/authorize?${params.toString()}`);
});

app.get("/auth/spotify/callback", async (req, res) => {
  if (!requireSpotifyConfig(res)) return;

  const { code, state, error } = req.query;
  if (error) {
    return res.status(400).type("text").send(`Spotify authorization failed: ${String(error)}`);
  }

  if (!verifyOAuthState(state)) {
    return res.status(400).type("text").send("Invalid or expired OAuth state. Please start Spotify login again.");
  }

  if (typeof code !== "string") {
    return res.status(400).type("text").send("Spotify did not return an authorization code.");
  }

  try {
    const credentials = Buffer.from(
      `${SPOTIFY_CLIENT_ID}:${SPOTIFY_CLIENT_SECRET}`
    ).toString("base64");

    const tokenResponse = await fetch("https://accounts.spotify.com/api/token", {
      method: "POST",
      headers: {
        Authorization: `Basic ${credentials}`,
        "Content-Type": "application/x-www-form-urlencoded"
      },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        code,
        redirect_uri: SPOTIFY_REDIRECT_URI
      })
    });

    const tokens = await tokenResponse.json();
    if (!tokenResponse.ok) {
      console.error("Spotify token exchange failed:", tokenResponse.status, tokens.error);
      return res.status(502).type("text").send("Spotify token exchange failed. Check Render logs.");
    }

    prune(loginTickets);
    const ticket = randomToken(24);
    loginTickets.set(ticket, {
      expiresAt: Date.now() + TICKET_TTL_MS,
      tokens: {
        access_token: tokens.access_token,
        refresh_token: tokens.refresh_token,
        token_type: tokens.token_type,
        expires_in: tokens.expires_in,
        scope: tokens.scope
      }
    });

    const redirect = new URL(APP_REDIRECT_URI);
    redirect.searchParams.set("ticket", ticket);
    return res.redirect(302, redirect.toString());
  } catch (err) {
    console.error("Spotify callback error:", err);
    return res.status(500).type("text").send("Could not complete Spotify authorization.");
  }
});

// The app exchanges a short-lived, single-use ticket for tokens over HTTPS.
app.post("/auth/spotify/exchange", (req, res) => {
  prune(loginTickets);
  const ticket = req.body?.ticket;
  if (typeof ticket !== "string" || ticket.length > 128) {
    return res.status(400).json({ error: "invalid_ticket" });
  }

  const entry = loginTickets.get(ticket);
  if (!entry || entry.expiresAt <= Date.now()) {
    loginTickets.delete(ticket);
    return res.status(401).json({ error: "ticket_expired" });
  }

  loginTickets.delete(ticket);
  return res.json(entry.tokens);
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`NET MUSIC API listening on port ${PORT}`);
});
