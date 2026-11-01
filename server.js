import express from "express";
import { attachCloudRoutes } from "./cloud.js";
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
app.use(express.json({ limit: "1mb" }));
attachCloudRoutes(app);

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
  if (!/^\d+$/.test(timestampText) || !nonce || !suppliedSignature) return false;

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


// Unified catalog search: the Android app talks only to NET MUSIC.
// Provider credentials and API details stay on this server.
const AUDIUS_BASE = "https://discoveryprovider.audius.co/v1";
const JAMENDO_CLIENT_ID = process.env.JAMENDO_CLIENT_ID?.trim();
const AUDIOUS_APP_NAME = "netmusic";

function safeText(value, fallback = "") {
  return typeof value === "string" ? value.trim() : fallback;
}

function normalizeAudius(track) {
  const id = safeText(track?.id);
  if (!id) return null;
  const artwork = track.artwork || {};
  return {
    id: `audius:${id}`,
    provider: "Audius",
    providerId: id,
    title: safeText(track.title, "Без названия"),
    artist: safeText(track.user?.name, "Неизвестный исполнитель"),
    artwork: safeText(artwork["480x480"] || artwork["150x150"] || artwork["1000x1000"]),
    duration: Math.max(0, Number(track.duration || 0)),
    streamUrl: `${AUDIUS_BASE}/tracks/${encodeURIComponent(id)}/stream?app_name=${AUDIOUS_APP_NAME}`,
    downloadUrl: null,
    downloadAllowed: false,
    license: safeText(track.license, "Условия загрузки не подтверждены"),
    externalUrl: safeText(track.permalink)
  };
}

function normalizeAppleTrack(track) {
  const id = track?.trackId == null ? "" : String(track.trackId);
  const preview = safeText(track?.previewUrl);
  if (!id || !preview) return null;
  return {
    id: `apple:${id}`,
    provider: "Apple Music · preview",
    providerId: id,
    title: safeText(track.trackName, "Без названия"),
    artist: safeText(track.artistName, "Неизвестный исполнитель"),
    artwork: safeText(track.artworkUrl100).replace("100x100", "600x600"),
    duration: Math.max(0, Math.floor(Number(track.trackTimeMillis || 0) / 1000)),
    streamUrl: preview,
    downloadUrl: null,
    downloadAllowed: false,
    license: "Официальный 30-секундный preview; полная версия доступна у правообладателя",
    externalUrl: safeText(track.trackViewUrl)
  };
}

function normalizeDeezerTrack(track) {
  const id = track?.id == null ? "" : String(track.id);
  const preview = safeText(track?.preview);
  if (!id || !preview) return null;
  return {
    id: `deezer:${id}`,
    provider: "Deezer · preview",
    providerId: id,
    title: safeText(track.title, "Без названия"),
    artist: safeText(track.artist?.name, "Неизвестный исполнитель"),
    artwork: safeText(track.album?.cover_xl || track.album?.cover_big || track.album?.cover),
    duration: Math.max(0, Number(track.duration || 0)),
    streamUrl: preview,
    downloadUrl: null,
    downloadAllowed: false,
    license: "Официальный preview; полная версия доступна у правообладателя",
    externalUrl: safeText(track.link)
  };
}

function normalizeJamendo(track) {
  const id = track?.id == null ? "" : safeText(String(track.id));
  if (!id) return null;
  const allowed = track.audiodownload_allowed === true || track.audiodownload_allowed === "true";
  return {
    id: `jamendo:${id}`,
    provider: "Jamendo",
    providerId: id,
    title: safeText(track.name, "Без названия"),
    artist: safeText(track.artist_name, "Неизвестный исполнитель"),
    artwork: safeText(track.album_image || track.image),
    duration: Math.max(0, Number(track.duration || 0)),
    streamUrl: safeText(track.audio),
    downloadUrl: allowed ? safeText(track.audiodownload) || `https://api.jamendo.com/v3.0/tracks/file/?client_id=${encodeURIComponent(JAMENDO_CLIENT_ID || "")}&id=${encodeURIComponent(id)}&audioformat=mp32` : null,
    downloadAllowed: allowed,
    license: safeText(track.license_ccurl, "Creative Commons — проверь условия лицензии"),
    externalUrl: safeText(track.shareurl)
  };
}

async function fetchJson(url, timeoutMs = 9000) {
  const response = await fetch(url, {
    headers: { Accept: "application/json" },
    signal: AbortSignal.timeout(timeoutMs)
  });
  if (!response.ok) throw new Error(`Provider returned HTTP ${response.status}`);
  return response.json();
}

function normalizeSearchText(value) {
  return String(value || "")
    .normalize("NFKC")
    .toLocaleLowerCase("ru")
    .replace(/ё/g, "е")
    .replace(/[’'"`]/g, "")
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim()
    .replace(/\s+/g, " ");
}

const RU_TO_LAT = {
  а:"a", б:"b", в:"v", г:"g", д:"d", е:"e", ж:"zh", з:"z", и:"i",
  й:"y", к:"k", л:"l", м:"m", н:"n", о:"o", п:"p", р:"r", с:"s",
  т:"t", у:"u", ф:"f", х:"kh", ц:"ts", ч:"ch", ш:"sh", щ:"shch",
  ы:"y", э:"e", ю:"yu", я:"ya", ь:"", ъ:""
};
const LAT_TO_RU = [
  [/shch/g,"щ"],[/sch/g,"щ"],[/yo/g,"ё"],[/zh/g,"ж"],[/kh/g,"х"],
  [/ts/g,"ц"],[/ch/g,"ч"],[/sh/g,"ш"],[/yu/g,"ю"],[/ya/g,"я"],
  [/ye/g,"е"],[/a/g,"а"],[/b/g,"б"],[/v/g,"в"],[/g/g,"г"],[/d/g,"д"],
  [/e/g,"е"],[/z/g,"з"],[/i/g,"и"],[/y/g,"й"],[/k/g,"к"],[/l/g,"л"],
  [/m/g,"м"],[/n/g,"н"],[/o/g,"о"],[/p/g,"п"],[/r/g,"р"],[/s/g,"с"],
  [/t/g,"т"],[/u/g,"у"],[/f/g,"ф"],[/h/g,"х"]
];

function transliterateQuery(value) {
  const text = normalizeSearchText(value);
  if (!text) return "";
  if (/[а-я]/i.test(text)) {
    return [...text].map(char => RU_TO_LAT[char] ?? char).join("");
  }
  let result = text;
  for (const [pattern, replacement] of LAT_TO_RU) result = result.replace(pattern, replacement);
  return result;
}

function searchScore(query, track) {
  const q = normalizeSearchText(query);
  const title = normalizeSearchText(track.title);
  const artist = normalizeSearchText(track.artist);
  const combined = title + " " + artist;
  if (!q) return 0;
  if (title === q) return 100;
  if (artist === q) return 92;
  if (title.startsWith(q)) return 84;
  if (combined.includes(q)) return 74;
  const tokens = q.split(" ");
  const matched = tokens.filter(token => combined.includes(token)).length;
  return Math.round((matched / tokens.length) * 60);
}

app.get("/api/catalog/search", async (req, res) => {
  const query = safeText(String(req.query.q || "")).slice(0, 120);
  if (query.length < 2) {
    return res.status(400).json({ error: "query_too_short", message: "Введите минимум 2 символа." });
  }

  const requestedLimit = Number.parseInt(String(req.query.limit || "20"), 10);
  const limit = Number.isFinite(requestedLimit) ? Math.min(30, Math.max(1, requestedLimit)) : 20;
  const alternate = transliterateQuery(query);
  const variants = [query, alternate].filter((value, index, all) => value && all.findIndex(candidate => normalizeSearchText(candidate) === normalizeSearchText(value)) === index).slice(0, 2);
  const jobs = [];
  const discoveryJobs = [];

  for (const searchTerm of variants) {
    for (const kind of ["artist", "album"]) {
      const discoveryUrl = new URL(`https://api.deezer.com/search/${kind}`);
      discoveryUrl.searchParams.set("q", searchTerm);
      discoveryUrl.searchParams.set("limit", String(Math.min(limit, 12)));
      discoveryJobs.push(fetchJson(discoveryUrl).then(data => ({
        kind,
        items: (Array.isArray(data?.data) ? data.data : []).map(item => ({
          id: `deezer:${kind}:${item.id}`,
          provider: "Deezer",
          type: kind,
          name: safeText(item.name || item.title),
          artwork: safeText(item.picture_xl || item.picture_big || item.cover_xl || item.cover_big || item.picture || item.cover),
          artist: safeText(item.artist?.name),
          externalUrl: safeText(item.link || (kind === "artist" ? `https://www.deezer.com/artist/${item.id}` : `https://www.deezer.com/album/${item.id}`))
        })).filter(item => item.name && item.externalUrl)
      })).catch(error => {
        console.warn(`[catalog] Deezer ${kind} search failed:`, error.message);
        return { kind, items: [] };
      }));
    }
    const audiusUrl = new URL(`${AUDIUS_BASE}/tracks/search`);
    audiusUrl.searchParams.set("query", searchTerm);
    audiusUrl.searchParams.set("limit", String(limit));
    audiusUrl.searchParams.set("app_name", AUDIOUS_APP_NAME);
    jobs.push(fetchJson(audiusUrl).then(data => ({
      provider: "Audius",
      tracks: (Array.isArray(data?.data) ? data.data : []).map(normalizeAudius).filter(Boolean)
    })).catch(error => {
      console.warn("[catalog] Audius search failed:", error.message);
      return { provider: "Audius", tracks: [], error: "unavailable" };
    }));

    for (const storefront of ["RU", "DE", "US"]) {
      const appleUrl = new URL("https://itunes.apple.com/search");
      appleUrl.searchParams.set("term", searchTerm);
      appleUrl.searchParams.set("entity", "song");
      appleUrl.searchParams.set("limit", String(limit));
      appleUrl.searchParams.set("country", storefront);
      jobs.push(fetchJson(appleUrl).then(data => ({
        provider: `Apple Music (${storefront})`,
        tracks: (Array.isArray(data?.results) ? data.results : []).map(normalizeAppleTrack).filter(Boolean)
      })).catch(error => {
        console.warn(`[catalog] Apple ${storefront} search failed:`, error.message);
        return { provider: `Apple Music (${storefront})`, tracks: [], error: "unavailable" };
      }));
    }

    const deezerUrl = new URL("https://api.deezer.com/search");
    deezerUrl.searchParams.set("q", searchTerm);
    deezerUrl.searchParams.set("limit", String(limit));
    jobs.push(fetchJson(deezerUrl).then(data => ({
      provider: "Deezer",
      tracks: (Array.isArray(data?.data) ? data.data : []).map(normalizeDeezerTrack).filter(Boolean)
    })).catch(error => {
      console.warn("[catalog] Deezer search failed:", error.message);
      return { provider: "Deezer", tracks: [], error: "unavailable" };
    }));

    if (JAMENDO_CLIENT_ID) {
      const jamendoUrl = new URL("https://api.jamendo.com/v3.0/tracks/");
      jamendoUrl.searchParams.set("client_id", JAMENDO_CLIENT_ID);
      jamendoUrl.searchParams.set("format", "json");
      jamendoUrl.searchParams.set("search", searchTerm);
      jamendoUrl.searchParams.set("limit", String(limit));
      jamendoUrl.searchParams.set("audioformat", "mp32");
      jamendoUrl.searchParams.set("include", "musicinfo");
      jobs.push(fetchJson(jamendoUrl).then(data => ({
        provider: "Jamendo",
        tracks: (Array.isArray(data?.results) ? data.results : []).map(normalizeJamendo).filter(track => track && track.streamUrl)
      })).catch(error => {
        console.warn("[catalog] Jamendo search failed:", error.message);
        return { provider: "Jamendo", tracks: [], error: "unavailable" };
      }));
    }
  }

  if (!JAMENDO_CLIENT_ID) jobs.push(Promise.resolve({ provider: "Jamendo", tracks: [], error: "not_configured" }));

  const [results, discoveryResults] = await Promise.all([
    Promise.all(jobs),
    Promise.all(discoveryJobs)
  ]);
  const artists = discoveryResults.filter(result => result.kind === "artist").flatMap(result => result.items);
  const albums = discoveryResults.filter(result => result.kind === "album").flatMap(result => result.items);
  const dedupeEntities = items => {
    const seen = new Set();
    return items.filter(item => {
      const key = normalizeSearchText(item.name) + "|" + normalizeSearchText(item.artist);
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    }).slice(0, limit);
  };
  const providerMap = new Map();
  for (const result of results) {
    const current = providerMap.get(result.provider) || { provider: result.provider, tracks: [], error: null };
    current.tracks.push(...result.tracks);
    if (result.error && !current.tracks.length) current.error = result.error;
    providerMap.set(result.provider, current);
  }

  const seen = new Set();
  const unique = [];
  for (const track of results.flatMap(result => result.tracks)) {
    const key = `${normalizeSearchText(track.title)}|${normalizeSearchText(track.artist)}`;
    if (seen.has(key)) continue;
    seen.add(key);
    const relevance = Math.max(...variants.map(term => searchScore(term, track)));
    // Provider search can return broad/noisy matches. Never show a result
    // that has no textual overlap with either the original or transliterated query.
    if (relevance < 20) continue;
    unique.push({ ...track, relevance });
  }
  unique.sort((a, b) => b.relevance - a.relevance || a.title.localeCompare(b.title, "ru"));
  const providers = [...providerMap.values()].map(({ provider, tracks: items, error }) => ({
    name: provider,
    count: new Set(items.map(track => track.id)).size,
    status: error || (items.length ? "ok" : "empty")
  }));

  res.set("Cache-Control", "public, max-age=20, stale-while-revalidate=40");
  return res.json({
    query,
    search: { normalized: normalizeSearchText(query), alternate: alternate !== query ? alternate : null, variants },
    tracks: unique.slice(0, limit * 2),
    artists: dedupeEntities(artists),
    albums: dedupeEntities(albums),
    providers
  });
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`NET MUSIC API listening on port ${PORT}`);
});
