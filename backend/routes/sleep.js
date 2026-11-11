// routes/sleep.js
// Mounted behind the same JWT middleware as watch.js, e.g.
//   app.use('/sleep', authMiddleware, require('./routes/sleep'));
const express = require('express');
const pool = require('../db');
const router = express.Router();

const MAX_SEGMENTS = 500;

function validate(body) {
  const errors = [];
  const segs = body && body.segments;
  if (!Array.isArray(segs) || segs.length === 0) return ['segments must be a non-empty array'];
  if (segs.length > MAX_SEGMENTS) return [`too many segments (max ${MAX_SEGMENTS})`];

  const maxStart = Date.now() + 5 * 60 * 1000;
  segs.forEach((s, i) => {
    const t = new Date(s && s.start_time);
    if (!s || !s.start_time || isNaN(t.getTime())) errors.push(`segments[${i}].start_time must be an ISO timestamp`);
    else if (t.getTime() > maxStart) errors.push(`segments[${i}].start_time is in the future`);
    if (!Number.isInteger(s && s.stage) || s.stage < 1 || s.stage > 3) errors.push(`segments[${i}].stage must be 1 (deep), 2 (light) or 3 (awake)`);
    if (!Number.isInteger(s && s.duration_min) || s.duration_min < 1 || s.duration_min > 1440) errors.push(`segments[${i}].duration_min must be 1-1440`);
  });
  return errors.slice(0, 10);
}

// POST /sleep  { segments: [{ start_time, stage, duration_min }, ...] }
router.post('/', async (req, res) => {
  const errors = validate(req.body);
  if (errors.length) return res.status(400).json({ errors });

  const userId = req.user.user_id;
  const segs = req.body.segments;
  const starts = segs.map((s) => new Date(s.start_time).toISOString());
  const stages = segs.map((s) => s.stage);
  const durations = segs.map((s) => s.duration_min);

  const startMs = segs.map((s) => new Date(s.start_time).getTime());
  const sessionStart = new Date(Math.min(...startMs)).toISOString();
  const sessionEnd = new Date(Math.max(...segs.map((s, i) => startMs[i] + s.duration_min * 60000))).toISOString();

  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    // Re-sent nights are ignored: (user_id, start_time) is unique.
    const ins = await client.query(
      `INSERT INTO sleep_segments (user_id, start_time, stage, duration_min)
       SELECT $1, t.start_time, t.stage, t.duration_min
       FROM unnest($2::timestamptz[], $3::smallint[], $4::int[]) AS t(start_time, stage, duration_min)
       ON CONFLICT (user_id, start_time) DO NOTHING`,
      [userId, starts, stages, durations],
    );

    // One session row per night; skipped if this watch night already exists.
    await client.query(
      `INSERT INTO sleep_sessions (user_id, start_time, end_time, source)
       SELECT $1, $2::timestamptz, $3::timestamptz, 'watch'
       WHERE NOT EXISTS (
         SELECT 1 FROM sleep_sessions
         WHERE user_id = $1 AND source = 'watch' AND start_time = $2::timestamptz
       )`,
      [userId, sessionStart, sessionEnd],
    );

    await client.query('COMMIT');
    const inserted = ins.rowCount;
    res.status(inserted > 0 ? 201 : 200).json({
      message: inserted > 0 ? 'Sleep data saved' : 'Night already stored',
      inserted,
      received: segs.length,
    });
  } catch (err) {
    await client.query('ROLLBACK');
    console.error('Sleep insertion failure:', err.message);
    res.status(500).json({ error: 'Failed to persist sleep data.' });
  } finally {
    client.release();
  }
});

module.exports = router;