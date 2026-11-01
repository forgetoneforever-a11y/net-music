export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const origin = (env.ORIGIN_API || "https://net-music.onrender.com").replace(/\\/$/, "");
    const allowedOrigin = env.APP_ORIGIN || "*";

    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: corsHeaders(allowedOrigin)
      });
    }

    // Only proxy the NET MUSIC API. Audio streams stay with their licensed providers.
    if (!url.pathname.startsWith("/api/") && !url.pathname.startsWith("/auth/") && url.pathname !== "/health") {
      return new Response("NET MUSIC Edge", { status: 200, headers: corsHeaders(allowedOrigin) });
    }

    const target = new URL(origin + url.pathname + url.search);
    const headers = new Headers(request.headers);
    headers.delete("host");
    headers.set("X-Forwarded-Host", url.host);
    headers.set("X-Forwarded-Proto", "https");

    const upstreamRequest = new Request(target, {
      method: request.method,
      headers,
      body: ["GET", "HEAD"].includes(request.method) ? undefined : request.body,
      redirect: "manual"
    });

    const cacheableSearch = request.method === "GET" && url.pathname === "/api/catalog/search";
    if (cacheableSearch) {
      const cache = caches.default;
      const cacheKey = new Request(target.toString(), { method: "GET" });
      const cached = await cache.match(cacheKey);
      if (cached) {
        const response = new Response(cached.body, cached);
        applyCors(response.headers, allowedOrigin);
        response.headers.set("X-NET-MUSIC-EDGE", "HIT");
        return response;
      }
    }

    try {
      const upstream = await fetch(upstreamRequest);
      const response = new Response(upstream.body, upstream);
      applyCors(response.headers, allowedOrigin);
      response.headers.set("X-NET-MUSIC-EDGE", "MISS");
      response.headers.set("Cache-Control", cacheableSearch ? "public, max-age=20" : "no-store");

      if (cacheableSearch && upstream.ok) {
        const cacheCopy = response.clone();
        ctx.waitUntil(caches.default.put(
          new Request(target.toString(), { method: "GET" }),
          cacheCopy
        ));
      }
      return response;
    } catch (_error) {
      return Response.json(
        { error: "edge_upstream_unavailable", message: "NET MUSIC API временно недоступен." },
        { status: 502, headers: corsHeaders(allowedOrigin) }
      );
    }
  }
};

function corsHeaders(origin) {
  return new Headers({
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Methods": "GET,POST,PUT,DELETE,OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type,Authorization",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin"
  });
}

function applyCors(headers, origin) {
  headers.set("Access-Control-Allow-Origin", origin);
  headers.set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
  headers.set("Access-Control-Allow-Headers", "Content-Type,Authorization");
  headers.set("Vary", "Origin");
}
