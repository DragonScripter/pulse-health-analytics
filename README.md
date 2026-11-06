# pulse-health-analytics

A personal health tracking app I'm putting together because my smartwatch (USMECBL) locks its data away, and I wanted a way to actually use my own biometric info. It syncs watch data into Android, sends it to a server, and lets me connect with friends/family(eventually) to share stats.

I'm also using this to screw around with some Machine Learning stuff for an AI class I'm taking before graduating.

## The Stack 
* **Frontend:** A native Android app written in **Kotlin** (XML layouts). It connects to the watch directly over **BLE**, decodes the data, and posts it to the backend.
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

Data from the watch is stored in two tiers, so nothing is lost and daily stats can be calculated later. The app posts each reading to `POST /api/watch-metrics`.

### Tier 1: raw samples
Every reading is stored as-is, with no cleaning or filtering for now.

| Table | Columns | Notes |
|---|---|---|
| `heart_rate` | `user_id`, `time`, `bpm` | Primary key `(user_id, time)`; duplicates are ignored so retries are safe |
| `spo2_samples` | `user_id`, `time`, `spo2` | Same key and behaviour |

- `time` is the **phone-side capture time** (UTC), not the server receive time, so delayed or retried posts land in the right place.
- Heart rate can arrive every few seconds. For now every sample is accepted, with no throttling or batching.
- These tables are the source of truth for the heart rate stats planned later: average, peak, lowest and heart rate recovery.

### Tier 2: daily totals (`daily_metrics`)
One row per user per day (`user_id`, `day`), with `spo2` (latest reading), `steps`, `distance_metres`, `calories_kcal` and `updated_at`.

- Each incoming reading **upserts** the row.
- Steps, distance and calories keep the highest value seen that day (`GREATEST(existing, incoming)`), so an out-of-order or delayed post can't move a total backwards.
- `day` is the user's local date, as sent by the phone.

### Request payload
```json
{
  "captured_at": "2026-11-03T19:30:00.123Z",
  "date": "2026-11-03",
  "heart_rate": 72,
  "spo2": 97,
  "steps": 4330,
  "distance_metres": 3210,
  "calories_kcal": 148
}
```
All metrics are optional, but at least one is required. Heart rate must be 20-250 and SpO2 1-100.

## How the watch connection works
The watch has no standard Heart Rate Service (`0x180D`), so the app connects by MAC address (set as `WATCH_MAC` in `local.properties`) and subscribes to vendor characteristics. It decodes the raw bytes on the fly with no GloryFit or Health Connect involved.

| Data | Service | Characteristic | Format |
|---|---|---|---|
| Heart rate, SpO2 | `0x55FF` | `0x33F2` | Last byte is the value; first byte identifies it (`E5` = heart rate, `34` = SpO2) |
| Steps, distance, calories | `0xFEE7` | `0xFEA1` | 1-byte header, then three little-endian 3-byte integers (distance in metres, calories in kcal) |

Heart rate, SpO2, steps, distance and calories are all confirmed against the watch screen.


## Sleep data (decoded, not yet implemented in the app)

Sleep is on its own service, separate from the live metrics. The packet layout and stage codes are decoded and checked against GloryFit for one night. The app does not read or store sleep yet.

| Service | Characteristic | Properties |
|---|---|---|
| `0x56FF` | `0x34F2` | NOTIFY |

The paired write characteristic is `0x34F1` (read / write no response). Whether a request must be written to it is **unverified**: in nRF Connect the sleep packet was already sitting on `0x34F2` with no write from us, so the watch may push it as soon as you subscribe.

### Packet format
One header byte, then fixed 6-byte records. The example night was 199 bytes: `0x32` plus 33 records.

```text
byte 0        header, 0x32 (meaning unknown; not the record count)
then, per 6-byte record:
  [hour] [minute] [stage] [01] [00] [duration in minutes]
```

- Hour and minute are plain binary (not BCD), as a time of day. **There is no date in the packet.**
- Records are contiguous: each start time equals the previous start plus its duration.
- Bytes 3 and 4 of every record were `01 00` in the sample, and their meaning is unknown.

| Stage code | Stage |
|---|---|
| `1` | Deep |
| `2` | Light |
| `3` | Awake |

### Verified against GloryFit (one night)
| Stage | From packet | GloryFit |
|---|---|---|
| Deep | 186 min | 3h 06m |
| Light | 168 min | 2h 48m |
| Awake | 20 min | 20m |
| **Total** | **374 min** | **6h 14m** |

The session ran 02:02 to 08:16, which is also 6h 14m.

First records of the sample:

| Raw bytes | Start | Stage | Duration |
|---|---|---|---|
| `02 02 02 01 00 0C` | 02:02 | light | 12 min |
| `02 0E 03 01 00 03` | 02:14 | awake | 3 min |
| `02 11 02 01 00 01` | 02:17 | light | 1 min |
| `02 12 01 01 00 1A` | 02:18 | deep | 26 min |


### Open questions
- Does subscribing alone deliver the packet, or is a request to `0x34F1` needed?
- What do the header `0x32` and the constant `01 00` bytes mean?
- Is there any packet for multiple nights, or only the most recent one?
- The watch's own sleep screen was empty after it synced to glory fit even though the data was still readable over BLE, so the watch screen is not a reliable indicator of what's stored.


### Not in scope yet
- Data cleaning / outlier filtering (e.g. spurious 0 or 255 readings)
- Pre-computed heart rate stats (calculated on demand from the raw tables)
- Sleep data: the watch stores it and only sends it on request, and the app doesn't decode it yet
- The AI service: planned, Node will forward readings from Postgres to it, but it's not built yet






- **Planned:** the app will forward readings to the Node.js backend, then Postgres and the AI service (not built yet).
- **How it works:** The watch has no standard Heart Rate Service (`0x180D`), so the app connects by MAC address and subscribes to vendor characteristics. It decodes the raw bytes on the fly with no GloryFit or Health Connect involved.
- **Heart rate and SpO2:** characteristic `0x33F2` (service `0x55FF`). The last byte is the value, and the first byte tells the two apart (`E5` = heart rate, `34` = SpO2).
- **Steps, distance, calories:** characteristic `0xFEA1` (service `0xFEE7`), packed as little-endian 3-byte integers. Distance is in metres and calories in kcal.
- **Status:** heart rate, SpO2, steps, distance, and calories are all confirmed against the watch screen.
- **TODO:** sleep isn't decoded yet. The watch stores it and only sends it on request


