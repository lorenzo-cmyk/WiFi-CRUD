# WiFi-CRUD

IoT Tracking Backend + Frontend su Cloudflare Workers e D1.

## Struttura

```
WiFi-CRUD/
├── backend/                    # API Worker
│   ├── src/index.ts            #   Endpoints REST
│   ├── wrangler.jsonc           #   Config (D1 binding)
│   └── schema.sql              #   Schema DB
├── src/index.ts                # Frontend Worker (SPA HTML + Tailwind CDN)
├── wrangler.jsonc              # Frontend config
├── package.json                # Scripts + dipendenze
├── app/                        # Android app (Kotlin)
│   ├── build.gradle.kts        #   Modulo Android
│   ├── settings.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/wificrud/app/
│       │   ├── MainActivity.kt
│       │   ├── api/ApiClient.kt
│       │   ├── data/CredentialStore.kt
│       │   └── scan/WifiScanner.kt
│       └── res/
└── AGENTS.md
```

## Deploy

| Worker | URL |
|--------|-----|
| Backend API | `https://wifi-crud.lorenzo-chiroli.workers.dev` |
| Frontend | `https://wifi-crud-frontend.lorenzo-chiroli.workers.dev` |

### Comandi

```bash
# Backend
npm run dev:backend        # wrangler dev --config backend/wrangler.jsonc
npm run deploy:backend     # deploy backend
npm run db:migrate         # esegui schema.sql su D1 remoto
npm run db:migrate:local   # esegui schema.sql su D1 locale

# Frontend
npm run dev               # wrangler dev (root)
npm run deploy            # deploy frontend
```

## Backend API

### Endpoints

#### `GET /health`
Health check. Return: `{"status":"ok"}`

#### `POST /api/devices/register`
Register a new device. No auth required.

- Body: `{"name": "device-name"}`
- Headers: `Content-Type: application/json`
- Response 201: `{"device_id": "uuid", "auth_key": "hex-key"}`
- L'auth_key va salvata dal device e usata per inviare misurazioni.

#### `POST /api/measurements`
Invia una misurazione. Autenticato con X-Device-Key.

- Headers: `X-Device-Key: <auth_key>`, `Content-Type: application/json`
- Body:
```json
{
  "timestamp": 1712345678,
  "gps_lat": 45.1234,
  "gps_lon": 7.5678,
  "wifi_scans": [
    {"ssid": "MyWiFi", "bssid": "00:11:22:33:44:55", "rssi": -65}
  ]
}
```
- `timestamp` opzionale (se omesso o < 1704067200 usa server time)
- `wifi_scans` array richiesto, max non limitato
- Response 201: `{"status": "success", "measurement_id": <id>}`

#### `POST /api/users/login`
Login utente.

- Body: `{"username": "...", "password": "..."}`
- Username e password vengono trimmed (leading/trailing whitespace rimosso)
- Response 200: `{"session_token": "hex", "expires_at": <unix>}`

#### `GET /api/devices`
Lista devices. Autenticato con Bearer token.

- Headers: `Authorization: Bearer <session_token>`
- Response: Array di `{id, name, created_at}`

#### `GET /api/measurements`
Query misurazioni. Autenticato con Bearer token.

- Headers: `Authorization: Bearer <session_token>`
- Query params:
  - `device_id` — filtra per device
  - `bssid` — filtra per bssid (INNER JOIN su wifi_scans)
  - `start_time` / `end_time` — range timestamp unix
  - `limit` (default 100, max 1000)
  - `offset` (default 0)
- Response: `{"measurements": [...], "total": <count>, "limit": N, "offset": N}`

## Schema DB

### Tables
- `devices` — id (TEXT PK), auth_key (TEXT UNIQUE), name (TEXT), created_at (INT)
- `measurements` — id (INTEGER PK AUTO), device_id (TEXT FK), timestamp (INT), gps_lat (REAL), gps_lon (REAL), created_at (INT)
- `wifi_scans` — id (INTEGER PK AUTO), measurement_id (INT FK), ssid (TEXT), bssid (TEXT), rssi (INT)
- `users` — id (TEXT PK), username (TEXT UNIQUE), password_hash (TEXT), created_at (INT)
- `user_sessions` — token (TEXT PK), user_id (TEXT FK), expires_at (INT)

### Indexes
- `idx_wifi_scans_bssid` ON wifi_scans(bssid)
- `idx_measurements_device_timestamp` ON measurements(device_id, timestamp)
- `idx_user_sessions_token` ON user_sessions(token)

## Credenziali di default
- Username: `admin`
- Password: `password_poc`

## Android App

### Flusso first-time setup
1. App chiede permessi: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` (e `NEARBY_WIFI_DEVICES` su API 33+)
2. Controlla se WiFi scan throttling è abilitato (Android 9+), mostra warning se sì
3. Registra il device via `POST /api/devices/register` (name = `Build.MODEL`)
4. Salva `device_id` e `auth_key` in SharedPreferences

### Funzionalità
- `CredentialStore` — wrapper SharedPreferences per device_id/auth_key/device_name
- `ApiClient` — HTTP client con `HttpURLConnection` (nessuna dependency esterna), chiamate a register + postMeasurement
- `WifiScanner` — wrapper attorno a `WifiManager` per scan + lettura risultati + throttle detection
- `MainActivity` — UI con setup wizard (permessi → throttle → register) e dashboard scan
- `ScanService` — foreground service persistente, loop periodico scan WiFi + GPS + API + notifica
- `ScanState` — singleton condiviso via StateFlow tra Service e Activity per UI live

### Flusso scanning
1. Utente imposta intervallo (5–120 secondi), preme "Start Scanning"
2. `ScanService` parte come foreground con notifica permanente
3. Ogni N secondi: GPS (last known) → scan WiFi → POST /api/measurements
4. Dashboard mostra conto alla rovescia, ultima misurazione (timestamp, GPS, networks)
5. Pulsante "Stop" per terminare il loop

### Build
```bash
cd app
./gradlew assembleDebug
```

## Note tecniche

- Cloudflare Free Tier: CPU 10ms limit. Nessuna dipendenza npm esterna per crypto/hashing.
- SHA-256 via Web Crypto API (`crypto.subtle.digest`)
- Password hashate con SHA-256 (sale non usato — POC)
- Device key generata con `crypto.getRandomValues` (32 bytes → hex)
- Session token: randomHex(32), scade dopo 7 giorni
- Timestamp validation: epoch-0 fallisce al server time
- D1 batch per insert atomico measurement + wifi_scans
- CORS aperto a `*`
- Password less login: errore generico "Invalid username or password" (non rivela quale dei due è sbagliato)
