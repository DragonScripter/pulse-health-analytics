Tickets for “Now” and “Next”
Now (milestone 1)

1. BLE foreground service. Move the watch connection into a `connectedDevice` foreground service with a persistent notification. Done when: the connection survives the app being backgrounded for 1 hour.
2. Auto-reconnect with backoff. Handle disconnects, retry with exponential backoff, and show connection status in the UI.
3. Battery optimization exemption prompt. Ask the user for the exemption on first run and explain why.
4. Local queue (Room). Write every reading locally first, with an `uploaded` flag. Done when: readings captured in airplane mode upload after reconnecting, with no gaps.
5. Batched uploads. Send the queue every 30-60 s. This may need a batch variant of `POST /api/watch-metrics` on the backend.
6. Sessions table and endpoints. Add `sessions` (`user_id`, `start`, `end`, `type`, `rpe`, `notes`) plus create/list endpoints.
7. Session start/stop button and RPE prompt. Include the type dropdown (sparring, kickboxing, lifting, conditioning, other).
8. Session stats endpoint. Average, peak, zone time and HR recovery, computed on demand from the raw `heart_rate` table.

Next (milestone 2)
11. Morning check-in screen (soreness, energy, mood) and its table.
12. Body weight entry and table.
13. Cleaned HR view: rolling median plus a plausibility filter, as a SQL view or service.
14. Daily baseline view: overnight resting HR, sleep, and 7-day load against the 28-day median.
Later (milestone 3)
15. Lifting log or CSV import from Strong/Hevy, then e1RM and volume load.
16. Acute:chronic load ratio.
17. AI service: weight velocity first, then readiness prediction.
18. Friends sharing, chest strap support.

