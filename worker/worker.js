// Cloudflare Worker: transparent proxy to the Telegram Bot API.
//
//  App request :  https://<name>.workers.dev/bot<TOKEN>/<method>
//  Forwarded to:  https://api.telegram.org/bot<TOKEN>/<method>
//
// If the app sends the placeholder token "env" (token field left empty in the app),
// the Worker uses the secret BOT_TOKEN stored in Cloudflare instead, so the token never
// touches the phone. Optionally set ALLOWED_CHAT_ID so the Worker only serves your chat.

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const m = url.pathname.match(/^\/bot([^/]+)\/([A-Za-z]+)$/);
    if (!m) {
      return new Response("Order Notebook Telegram relay is running.", { status: 200 });
    }
    let [, token, method] = m;
    if (token === "env") {
      if (!env.BOT_TOKEN) return json({ ok: false, description: "BOT_TOKEN secret is not set on the Worker" }, 500);
      token = env.BOT_TOKEN;
    }
    const allowed = ["getMe", "sendMessage", "sendPhoto", "sendDocument"];
    if (!allowed.includes(method)) return json({ ok: false, description: "method not allowed" }, 403);

    if (env.ALLOWED_CHAT_ID && method !== "getMe") {
      // chat_id is checked on JSON bodies; multipart uploads are checked via a clone of the form data
      try {
        const ct = request.headers.get("content-type") || "";
        let chat;
        if (ct.includes("application/json")) chat = String((await request.clone().json()).chat_id);
        else if (ct.includes("multipart/form-data")) chat = String((await request.clone().formData()).get("chat_id"));
        if (chat !== String(env.ALLOWED_CHAT_ID)) return json({ ok: false, description: "chat not allowed" }, 403);
      } catch (e) {
        return json({ ok: false, description: "bad request" }, 400);
      }
    }

    const target = `https://api.telegram.org/bot${token}/${method}`;
    const resp = await fetch(target, {
      method: request.method,
      headers: request.headers,
      body: request.method === "GET" ? undefined : request.body,
    });
    return new Response(resp.body, { status: resp.status, headers: { "content-type": "application/json" } });
  },
};

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });
}
