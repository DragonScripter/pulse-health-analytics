# pulse-health-analytics

A personal health tracking app I'm putting together because my smartwatch (USMECBL) locks its data away, and I wanted a way to actually use my own biometric info. It syncs watch data into Android, sends it to a server, and lets me connect with friends/family(eventually) to share stats.

I'm also using this to screw around with some Machine Learning stuff for an AI class I'm taking before graduating.

## The Stack 
* **Frontend:** A native Android App written in **Kotlin** (Jetpack Compose). It hooks into **Android Health Connect** to pull steps, heart rate, and sleep data out of the phone.
* **Main Backend:** **Node.js (Express)**. I'm comfortable with Node, so this handles the easy stuff: user logins, saving data to **PostgreSQL**, and managing the friends list(later).
* **AI Service:** A small **Python** container. I didn't want to write a full backend in Python, so Node just passes arrays of data to this Python container whenever I want to calculate a linear regression trend line (like 7-day weight velocity or heart rate changes over time).
* **Environment:** **Docker Compose** to spin up Node, Python, and Postgres locally.

## Folder Layout
```text
pulse-health-analystics/
├── android/       # The Kotlin Android App
├── backend/      # Node.js Express server (The middleman)
├── ai-service/           # Python script for Linear Regression math
└── docker-compose.yml    # Pins the backend containers together
```

## Data Flow for now
1. Watch sends data to its crappy app over Bluetooth.
2. Crappy app syncs it locally into Android **Health Connect**.
3. My App reads it from Health Connect and hits a Node.js endpoint with a massive JSON payload.
4. **Node.js** saves it to Postgres.
5. When I want an AI trend check, Node forwards the history to **Python** via an internal HTTP request.
6. Python calculates the slope ($y = mx + b$) using Scikit-Learn and tells back to Node if my health metrics are trending down or looking good.


## Reconfigured Data Pipeline

Old Architecture (Deprecating)
### Before (Deprecated due to sync bugs)
```text
[USMECBL Watch] ──> [GloryFit App] ──(Sync/Writing Errors)──> [Health Connect] ──> [My App] ──> [Node Server]
```
* **The Issue:** GloryFit suffered critical sync failures, producing broken records and massive data inconsistency gaps inside Android Health Connect.

### Target (In Development / Active Goal Direct Pipeline)
```text
[USMECBL Watch] ───(Direct Raw BLE Stream)───> [My Android App] ───(JSON/HTTP)───> [Node.js Backend]
                                                                                           │
                                              ┌────────────────────────────────────────────┘
                                              ▼
                                      ┌───────────────┐        (Internal HTTP)        ┌───────────────┐
                                      │  PostgreSQL   │ <───────────────────────────> │  AI Service   │
                                      │ (Persistence) │                               │ (Scikit-Learn)│
                                      └───────────────┘                               └───────────────┘
```

## Watch data storage

Data from the watch is stored in two tiers, so nothing is lost and daily stats can be calculated later.

### Tier 1: raw samples (`watch_samples`)
Every reading from the watch is stored as-is, with no cleaning or filtering for now.

| Column | Notes |
|---|---|
| `userId` | From the auth token |
| `metric` | `heart_rate` or `spo2` |
| `value` | Integer |
| `capturedAt` | **Phone-side capture time** (UTC), not server receive time, so delayed or retried posts land in the right place |

- Unique constraint on `(userId, metric, capturedAt)`; on conflict, ignore, so retries are safe.
- Heart rate can arrive every few seconds. For now every sample is accepted, with no throttling or batching.
- This is the source of truth for the heart rate stats planned later: average, peak, lowest and heart rate recovery (peak to a reading 1-2 minutes after the workout ends).

### Tier 2: daily totals (`watch_daily`)
Steps, distance and calories are cumulative counters on the watch, so they are stored as one row per user per day.

| Column | Notes |
|---|---|
| `userId` | From the auth token |
| `date` | The user's local date, as sent by the phone |
| `steps` | Integer |
| `distanceMetres` | Integer |
| `caloriesKcal` | Integer |
| `updatedAt` | Last upsert time |

- Unique on `(userId, date)`; each incoming reading **upserts** the row.
- Keep the highest value seen for the day (`GREATEST(existing, incoming)`) so an out-of-order or delayed post can't move a total backwards.

### Not in scope yet
- Data cleaning / outlier filtering (e.g. spurious 0 or 255 readings)
- Pre-computed heart rate stats (calculated on demand from `watch_samples`)
- Sleep data (the watch only sends it on request and the app doesn't decode it yet)






- **Planned:** the app will forward readings to the Node.js backend, then Postgres and the AI service (not built yet).
- **How it works:** The watch has no standard Heart Rate Service (`0x180D`), so the app connects by MAC address and subscribes to vendor characteristics. It decodes the raw bytes on the fly with no GloryFit or Health Connect involved.
- **Heart rate and SpO2:** characteristic `0x33F2` (service `0x55FF`). The last byte is the value, and the first byte tells the two apart (`E5` = heart rate, `34` = SpO2).
- **Steps, distance, calories:** characteristic `0xFEA1` (service `0xFEE7`), packed as little-endian 3-byte integers. Distance is in metres and calories in kcal.
- **Status:** heart rate, SpO2, steps, distance, and calories are all confirmed against the watch screen.
- **TODO:** sleep isn't decoded yet. The watch stores it and only sends it on request


