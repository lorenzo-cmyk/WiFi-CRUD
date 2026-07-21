interface Env {
  DB: D1Database;
}

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Device-Key",
  "Access-Control-Max-Age": "86400",
};

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", ...corsHeaders },
  });
}

function err(message: string, status = 400): Response {
  return json({ error: message }, status);
}

async function sha256(input: string): Promise<string> {
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(input));
  return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomHex(bytes = 32): string {
  const buf = new Uint8Array(bytes);
  crypto.getRandomValues(buf);
  return Array.from(buf).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function getAuthUser(request: Request): string | null {
  const h = request.headers.get("Authorization");
  if (!h || !h.startsWith("Bearer ")) return null;
  return h.slice(7);
}

function getDeviceKey(request: Request): string | null {
  return request.headers.get("X-Device-Key");
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const { pathname } = url;
    const method = request.method;

    if (method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    try {
      if (pathname === "/api/devices/register" && method === "POST") {
        return registerDevice(request, env);
      }
      if (pathname === "/api/measurements" && method === "POST") {
        return postMeasurement(request, env);
      }
      if (pathname === "/api/users/login" && method === "POST") {
        return loginUser(request, env);
      }
      if (pathname === "/api/devices" && method === "GET") {
        return listDevices(request, env);
      }
      if (pathname === "/api/measurements" && method === "GET") {
        return getMeasurements(request, env);
      }
      return err("Not found", 404);
    } catch (e) {
      console.error(JSON.stringify({ error: e instanceof Error ? e.message : String(e), path: pathname }));
      return err("Internal server error", 500);
    }
  },
} satisfies ExportedHandler<Env>;

async function registerDevice(request: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown>;
  try { body = await request.json(); } catch { return err("Invalid JSON", 400); }
  if (!body.name || typeof body.name !== "string") return err('"name" (string) is required', 400);

  const id = crypto.randomUUID();
  const key = randomHex(32);
  const now = Math.floor(Date.now() / 1000);

  await env.DB.prepare("INSERT INTO devices (id, auth_key, name, created_at) VALUES (?, ?, ?, ?)")
    .bind(id, key, body.name, now).run();

  return json({ device_id: id, auth_key: key }, 201);
}

async function postMeasurement(request: Request, env: Env): Promise<Response> {
  const deviceKey = getDeviceKey(request);
  if (!deviceKey) return err("Missing X-Device-Key header", 401);

  const device = await env.DB.prepare("SELECT id FROM devices WHERE auth_key = ?")
    .bind(deviceKey).first<{ id: string }>();
  if (!device) return err("Invalid device key", 401);

  let body: {
    timestamp?: number;
    gps_lat?: number;
    gps_lon?: number;
    wifi_scans?: Array<{ ssid?: string; bssid: string; rssi: number }>;
  };
  try { body = await request.json(); } catch { return err("Invalid JSON", 400); }
  if (!body.wifi_scans || !Array.isArray(body.wifi_scans)) {
    return err('"wifi_scans" array is required', 400);
  }

  const MIN_TS = 1704067200;
  const ts = body.timestamp && body.timestamp >= MIN_TS ? body.timestamp : Math.floor(Date.now() / 1000);
  const now = Math.floor(Date.now() / 1000);

  const meas = await env.DB.prepare(
    "INSERT INTO measurements (device_id, timestamp, gps_lat, gps_lon, created_at) VALUES (?, ?, ?, ?, ?)"
  ).bind(device.id, ts, body.gps_lat ?? null, body.gps_lon ?? null, now).run();

  const measId = meas.meta.last_row_id;
  if (!measId) return err("Failed to create measurement", 500);

  if (body.wifi_scans.length > 0) {
    const stmt = env.DB.prepare(
      "INSERT INTO wifi_scans (measurement_id, ssid, bssid, rssi) VALUES (?, ?, ?, ?)"
    );
    await env.DB.batch(body.wifi_scans.map((s) => stmt.bind(measId, s.ssid ?? null, s.bssid, s.rssi)));
  }

  return json({ status: "success", measurement_id: measId }, 201);
}

async function loginUser(request: Request, env: Env): Promise<Response> {
  let body: { username?: string; password?: string };
  try { body = await request.json(); } catch { return err("Invalid JSON", 400); }
  if (!body.username || !body.password) return err('"username" and "password" are required', 400);

  const user = await env.DB.prepare("SELECT id, password_hash FROM users WHERE username = ?")
    .bind(body.username).first<{ id: string; password_hash: string }>();
  if (!user) return err("Invalid username or password", 401);

  const hash = await sha256(body.password);
  if (hash !== user.password_hash) return err("Invalid username or password", 401);

  const token = randomHex(32);
  const now = Math.floor(Date.now() / 1000);
  const exp = now + 7 * 86400;

  await env.DB.prepare("INSERT INTO user_sessions (token, user_id, expires_at) VALUES (?, ?, ?)")
    .bind(token, user.id, exp).run();

  return json({ session_token: token, expires_at: exp });
}

async function listDevices(request: Request, env: Env): Promise<Response> {
  const session = await requireUser(request, env);
  if (!session) return err("Unauthorized", 401);

  const { results } = await env.DB.prepare(
    "SELECT id, name, created_at FROM devices ORDER BY created_at DESC"
  ).all();
  return json(results);
}

async function getMeasurements(request: Request, env: Env): Promise<Response> {
  const session = await requireUser(request, env);
  if (!session) return err("Unauthorized", 401);

  const url = new URL(request.url);
  const deviceId = url.searchParams.get("device_id");
  const startTime = url.searchParams.get("start_time");
  const endTime = url.searchParams.get("end_time");
  const bssid = url.searchParams.get("bssid");
  const limit = Math.min(Math.max(parseInt(url.searchParams.get("limit") || "100", 10) || 100, 1), 1000);
  const offset = Math.max(parseInt(url.searchParams.get("offset") || "0", 10) || 0, 0);

  const conditions: string[] = [];
  const params: (string | number)[] = [];

  if (deviceId) { conditions.push("m.device_id = ?"); params.push(deviceId); }
  if (startTime) { conditions.push("m.timestamp >= ?"); params.push(parseInt(startTime, 10)); }
  if (endTime) { conditions.push("m.timestamp <= ?"); params.push(parseInt(endTime, 10)); }

  const join = bssid ? " INNER JOIN wifi_scans w ON m.id = w.measurement_id" : "";
  let where = conditions.length ? "WHERE " + conditions.join(" AND ") : "";
  if (bssid) {
    where += where ? " AND w.bssid = ?" : "WHERE w.bssid = ?";
    params.push(bssid);
  }

  const distinct = bssid ? "DISTINCT " : "";
  const countSql = `SELECT COUNT(${bssid ? "DISTINCT m.id" : "*"}) as total FROM measurements m${join} ${where}`;
  const dataSql = `SELECT ${distinct}m.id, m.device_id, m.timestamp, m.gps_lat, m.gps_lon, m.created_at FROM measurements m${join} ${where} ORDER BY m.timestamp DESC LIMIT ? OFFSET ?`;

  const [countRes, dataRes] = await env.DB.batch([
    env.DB.prepare(countSql).bind(...params),
    env.DB.prepare(dataSql).bind(...params, limit, offset),
  ]);

  const measurements = dataRes.results as Array<{ id: number }>;
  const total = (countRes.results?.[0] as { total: number } | undefined)?.total ?? 0;

  if (measurements.length > 0) {
    const ids = measurements.map((m) => m.id);
    const placeholders = ids.map(() => "?").join(",");
    const { results: scans } = await env.DB.prepare(
      `SELECT measurement_id, ssid, bssid, rssi FROM wifi_scans WHERE measurement_id IN (${placeholders})`
    ).bind(...ids).all();

    const grouped = new Map<number, Array<{ ssid: string | null; bssid: string; rssi: number }>>();
    for (const s of scans as Array<{ measurement_id: number; ssid: string | null; bssid: string; rssi: number }>) {
      const list = grouped.get(s.measurement_id) ?? [];
      list.push({ ssid: s.ssid, bssid: s.bssid, rssi: s.rssi });
      grouped.set(s.measurement_id, list);
    }
    for (const m of measurements) {
      (m as Record<string, unknown>).wifi_scans = grouped.get(m.id) ?? [];
    }
  }

  return json({ measurements, total, limit, offset });
}

async function requireUser(request: Request, env: Env): Promise<{ id: string } | null> {
  const token = getAuthUser(request);
  if (!token) return null;
  const now = Math.floor(Date.now() / 1000);
  return env.DB.prepare("SELECT user_id AS id FROM user_sessions WHERE token = ? AND expires_at > ?")
    .bind(token, now).first<{ id: string }>();
}
