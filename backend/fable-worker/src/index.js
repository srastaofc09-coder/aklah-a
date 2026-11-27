export default {
  async fetch(request, env) {
    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization",
      "Access-Control-Max-Age": "86400"
    };
    const json = (data, status = 200) => new Response(JSON.stringify(data), {
      status, headers: { ...cors, "Content-Type": "application/json; charset=utf-8" }
    });
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    const url = new URL(request.url);
    if (url.pathname === "/health" && request.method === "GET") {
      return json({ ok: true, service: "AKLAH AI Fable proxy", configured: Boolean(env.FABLE_API_KEY) });
    }
    if (url.pathname === "/api/text" && request.method === "POST") {
      if (!env.GROQ_API_KEY) return json({ error: "GROQ_API_KEY server secret is not configured" }, 503);
      let body;
      try { body = await request.json(); } catch { return json({ error: "Invalid JSON body" }, 400); }
      const upstream = await fetch("https://api.groq.com/openai/v1/chat/completions", {
        method: "POST",
        headers: { "Authorization": "Bearer " + env.GROQ_API_KEY, "Content-Type": "application/json" },
        body: JSON.stringify(body)
      });
      return new Response(await upstream.text(), { status: upstream.status, headers: { ...cors, "Content-Type": "application/json; charset=utf-8" } });
    }

    if (url.pathname === "/api/image" && request.method === "GET") {
      if (!env.POLLINATIONS_API_KEY) return json({ error: "POLLINATIONS_API_KEY server secret is not configured" }, 503);
      const prompt = (url.searchParams.get("prompt") || "").trim();
      if (!prompt || prompt.length > 4000) return json({ error: "Prompt must be 1–4000 characters" }, 400);
      const upstreamUrl = "https://gen.pollinations.ai/image/" + encodeURIComponent(prompt) + "?model=black-forest-labs/flux.1-schnell&width=1024&height=1024&safe=true";
      const upstream = await fetch(upstreamUrl, { headers: { "Authorization": "Bearer " + env.POLLINATIONS_API_KEY, "Accept": "image/*" } });
      if (!upstream.ok) return json({ error: "Image provider returned HTTP " + upstream.status }, upstream.status);
      const headers = new Headers(cors);
      headers.set("Content-Type", upstream.headers.get("Content-Type") || "image/jpeg");
      headers.set("Cache-Control", "no-store");
      return new Response(upstream.body, { status: 200, headers });
    }

    if (!env.FABLE_API_KEY) return json({ error: "FABLE_API_KEY server secret is not configured" }, 503);

    if (url.pathname === "/api/video/create" && request.method === "POST") {
      let body;
      try { body = await request.json(); } catch { return json({ error: "Invalid JSON body" }, 400); }
      const prompt = typeof body.prompt === "string" ? body.prompt.trim() : "";
      const duration = Number(body.duration);
      if (!prompt || prompt.length > 12000) return json({ error: "Prompt must be 1–12000 characters" }, 400);
      if (!Number.isInteger(duration) || duration < 2 || duration > 30) {
        return json({ error: "Each Fable scene must be 2–30 seconds; create long videos from multiple scenes." }, 400);
      }
      const model = ["seedance-2.0-fast", "seedance-2.0", "seedance-2.5"].includes(body.model)
        ? body.model : "seedance-2.0-fast";
      const payload = {
        model, prompt, duration,
        quality: ["480p", "720p", "1080p"].includes(body.quality) ? body.quality : "720p",
        aspect_ratio: ["16:9", "9:16", "1:1"].includes(body.aspect_ratio) ? body.aspect_ratio : "9:16",
        generate_audio: Boolean(body.generate_audio)
      };
      const upstream = await fetch("https://www.fable.movie/v1/videos/generations", {
        method: "POST",
        headers: { "Authorization": "Bearer " + env.FABLE_API_KEY, "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });
      const text = await upstream.text();
      let data;
      try { data = JSON.parse(text); } catch { data = { error: { message: text.slice(0, 1000) } }; }
      return json(data, upstream.status);
    }

    const match = url.pathname.match(/^\/api\/video\/task\/([A-Za-z0-9_-]{6,100})$/);
    if (match && request.method === "GET") {
      const upstream = await fetch("https://www.fable.movie/v1/tasks/" + encodeURIComponent(match[1]), {
        headers: { "Authorization": "Bearer " + env.FABLE_API_KEY, "Accept": "application/json" }
      });
      const text = await upstream.text();
      let data;
      try { data = JSON.parse(text); } catch { data = { error: { message: text.slice(0, 1000) } }; }
      return json(data, upstream.status);
    }
    return json({ error: "Not found" }, 404);
  }
};
