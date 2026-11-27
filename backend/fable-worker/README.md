# AKLAH AI — private Fable API proxy

This Worker keeps the Fable API key off the Android APK. The Android app must call this Worker, never Fable directly.

## Deploy from a phone

1. Open Cloudflare dashboard and create a Worker named `aklah-fable-api`.
2. Paste the contents of `src/index.js` into the Worker editor and deploy.
3. In Worker Settings → Variables and Secrets, add a **secret** named `FABLE_API_KEY` with a newly generated Fable API key. Do not put the key in this repository or in the APK.
4. Add a Cloudflare rate limit rule to the Worker route to protect your credit balance.
5. Copy the Worker URL. The Android app's `FABLE_BACKEND_URL` constant must point to that URL before building.

## API

- `GET /health` — confirms the Worker is running and whether the secret is configured (does not reveal it).
- `POST /api/video/create` — JSON: `{"prompt":"...","duration":10,"model":"seedance-2.0-fast","quality":"720p","aspect_ratio":"9:16","generate_audio":true}`
- `GET /api/video/task/{task_id}` — check task status; completed tasks include `output.video_url`.

Fable creates one scene per task (model duration limits apply). A 5–6 minute film requires many scenes, multiple paid/credit-based generation tasks, and a separate video stitching/export step. This proxy does not make generation unlimited or free.

## Local CLI deployment (optional)

Install Wrangler in a trusted environment, then run:
```sh
wrangler login
wrangler secret put FABLE_API_KEY
wrangler deploy
```
