function unauthorized() {
  return new Response("Unauthorized", {
    status: 401,
    headers: {
      "WWW-Authenticate": 'Basic realm="solarlab-gradle-cache"',
    },
  });
}

function parseBasicAuth(headerValue) {
  if (!headerValue || !headerValue.startsWith("Basic ")) {
    return null;
  }

  const encoded = headerValue.slice("Basic ".length);
  try {
    const decoded = atob(encoded);
    const separatorIndex = decoded.indexOf(":");
    if (separatorIndex === -1) {
      return null;
    }
    return {
      username: decoded.slice(0, separatorIndex),
      password: decoded.slice(separatorIndex + 1),
    };
  } catch {
    return null;
  }
}

function isAuthorized(request, env) {
  const parsed = parseBasicAuth(request.headers.get("authorization"));
  if (!parsed) {
    return false;
  }
  return parsed.username === env.CACHE_BASIC_AUTH_USER && parsed.password === env.CACHE_BASIC_AUTH_PASS;
}

function cacheKeyFromUrl(requestUrl) {
  const url = new URL(requestUrl);
  if (!url.pathname.startsWith("/cache/")) {
    return null;
  }
  const key = url.pathname.slice("/cache/".length);
  return key.length > 0 ? `v1/${key}` : null;
}

export default {
  async fetch(request, env) {
    if (!isAuthorized(request, env)) {
      return unauthorized();
    }

    const cacheKey = cacheKeyFromUrl(request.url);
    if (!cacheKey) {
      return new Response("Not Found", { status: 404 });
    }

    if (request.method === "GET" || request.method === "HEAD") {
      const object = await env.GRADLE_CACHE_BUCKET.get(cacheKey);
      if (!object) {
        return new Response("Not Found", { status: 404 });
      }
      const headers = new Headers();
      object.writeHttpMetadata(headers);
      headers.set("etag", object.httpEtag);
      if (request.method === "HEAD") {
        return new Response(null, { status: 200, headers });
      }
      return new Response(object.body, { status: 200, headers });
    }

    if (request.method === "PUT") {
      const contentLength = Number(request.headers.get("content-length") ?? "0");
      const maxBytes = Number(env.MAX_CACHE_OBJECT_BYTES ?? "0");
      if (maxBytes > 0 && contentLength > maxBytes) {
        return new Response("Payload too large", { status: 413 });
      }
      await env.GRADLE_CACHE_BUCKET.put(cacheKey, request.body, {
        httpMetadata: {
          contentType: request.headers.get("content-type") ?? "application/octet-stream",
        },
      });
      return new Response(null, { status: 200 });
    }

    return new Response("Method Not Allowed", {
      status: 405,
      headers: {
        Allow: "GET, HEAD, PUT",
      },
    });
  },
};
