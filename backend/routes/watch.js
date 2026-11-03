// routes/watch.js
const express = require('express');
const pool = require('../db');
const router = express.Router();

const isInt = (v, min, max) => Number.isInteger(v) && v >= min && v <= max;
const present = (v) => v !== undefined && v !== null;

function validate(body) {
  const { captured_at, date, heart_rate, spo2, steps, distance_metres, calories_kcal } = body;
  const errors = [];

  const ts = new Date(captured_at);
  if (!captured_at || isNaN(ts.getTime())) errors.push('captured_at must be an ISO timestamp');
  else if (ts.getTime() > Date.now() + 5 * 60 * 1000) errors.push('captured_at is in the future');

  if (!/^\d{4}-\d{2}-\d{2}$/.test(date || '')) errors.push('date must be YYYY-MM-DD');

  if (present(heart_rate) && !isInt(heart_rate, 20, 250)) errors.push('heart_rate out of range (20-250)');
  if (present(spo2) && !isInt(spo2, 1, 100)) errors.push('spo2 out of range (1-100)');
  if (present(steps) && !isInt(steps, 0, 1_000_000)) errors.push('steps invalid');
  if (present(distance_metres) && !isInt(distance_metres, 0, 1_000_000)) errors.push('distance_metres invalid');
  if (present(calories_kcal) && !isInt(calories_kcal, 0, 100_000)) errors.push('calories_kcal invalid');

  const hasAny = [heart_rate, spo2, steps, distance_metres, calories_kcal].some(present);
  if (!hasAny) errors.push('at least one metric is required');

  return errors;
}

// POST /watch-metrics
router.post('/', async (req, res) => {
  console.log(`WATCH DATA AT: ${new Date().toISOString()}`);
  console.log(JSON.stringify(req.body, null, 2));

  const errors = validate(req.body || {});
  if (errors.length) return res.status(400).json({ errors });

  const userId = req.user.user_id;
  const { captured_at, date, heart_rate, spo2, steps, distance_metres, calories_kcal } = req.body;

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    // Tier 1: raw samples. Duplicate (user_id, time) is ignored so retries are safe.
    if (present(heart_rate)) {
      await client.query(
        `INSERT INTO heart_rate (user_id, time, bpm)
         VALUES ($1, $2, $3)
         ON CONFLICT (user_id, time) DO NOTHING`,
        [userId, captured_at, heart_rate],
      );
    }

    if (present(spo2)) {
      await client.query(
        `INSERT INTO spo2_samples (user_id, time, spo2)
         VALUES ($1, $2, $3)
         ON CONFLICT (user_id, time) DO NOTHING`,
        [userId, captured_at, spo2],
      );
    }

    // Tier 2: daily row. spo2 = latest reading; steps/distance/calories keep the
    // highest value seen so a late or out-of-order post can't move a total backwards.
    if ([spo2, steps, distance_metres, calories_kcal].some(present)) {
      await client.query(
        `INSERT INTO daily_metrics (user_id, day, spo2, steps, distance_metres, calories_kcal, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, now())
         ON CONFLICT (user_id, day) DO UPDATE SET
           spo2 = COALESCE(EXCLUDED.spo2, daily_metrics.spo2),
           steps = CASE WHEN EXCLUDED.steps IS NULL THEN daily_metrics.steps
                        ELSE GREATEST(COALESCE(daily_metrics.steps, 0), EXCLUDED.steps) END,
           distance_metres = CASE WHEN EXCLUDED.distance_metres IS NULL THEN daily_metrics.distance_metres
                        ELSE GREATEST(COALESCE(daily_metrics.distance_metres, 0), EXCLUDED.distance_metres) END,
           calories_kcal = CASE WHEN EXCLUDED.calories_kcal IS NULL THEN daily_metrics.calories_kcal
                        ELSE GREATEST(COALESCE(daily_metrics.calories_kcal, 0), EXCLUDED.calories_kcal) END,
           updated_at = now()`,
        [userId, date, spo2 ?? null, steps ?? null, distance_metres ?? null, calories_kcal ?? null],
      );
    }

    await client.query('COMMIT');
    res.status(201).json({ message: 'Watch data saved' });
  } catch (err) {
    await client.query('ROLLBACK');
    console.error('Watch metrics insertion failure:', err.message);
    res.status(500).json({ error: 'Failed to persist watch data.' });
  } finally {
    client.release();
  }
});

module.exports = router;