const express = require('express');
const pool = require('../db');
const router = express.Router();

router.post('/', async (req, res) => {
  console.log(`WORKOUT SUBMISSION AT: ${new Date().toISOString()}`);
  console.log(JSON.stringify(req.body, null, 2));

  const userId = req.user.user_id;
  const exerciseName =
    typeof req.body.exercise_name === 'string' ? req.body.exercise_name.trim() : '';
  const sets = Number(req.body.sets);
  const reps = Number(req.body.reps);
  const weight = Number(req.body.weight);

  const valid =
    exerciseName.length > 0 && exerciseName.length <= 100 &&
    Number.isInteger(sets) && sets >= 1 && sets <= 100 &&
    Number.isInteger(reps) && reps >= 1 && reps <= 1000 &&
    Number.isFinite(weight) && weight >= 0 && weight <= 2000;

  if (!valid) {
    return res.status(400).json({ error: 'Invalid workout data.' });
  }

  const totalVolume = sets * reps * weight;

  try {
    await pool.query(
      `INSERT INTO workouts (user_id, exercise_name, sets, reps, weight)
      VALUES ($1, $2, $3, $4, $5)`,
      [userId, exerciseName, sets, reps, weight],
    );

    console.log(`[SUCCESS] Saved ${exerciseName} session to Postgres`);

    res.status(200).json({
      message: 'Workout tracked successfully',
      ai_analysis: {
        volume_slope: 0.0,
        recommendation: `Saved! Volume for this entry: ${totalVolume} lbs.`,
      },
    });
  } catch (err) {
    console.error('Postgres Insertion Failure:', err.message);
    res.status(500).json({ error: 'Failed to persist metrics to database.' });
  }
});

router.get('/summary', async (req, res) => {
  const userId = req.user.user_id;
  try {
    const week = await pool.query(
      `SELECT COUNT(*)::int AS entries,
              COALESCE(SUM(sets * reps * weight), 0)::float AS volume
         FROM workouts
        WHERE user_id = $1
          AND created_at >= now() - interval '7 days'`,
      [userId],
    );
    const last = await pool.query(
      `SELECT exercise_name, sets, reps, weight::float AS weight, created_at
         FROM workouts
        WHERE user_id = $1
        ORDER BY created_at DESC
        LIMIT 1`,
      [userId],
    );
    res.json({
      week_entries: week.rows[0].entries,
      week_volume: week.rows[0].volume,
      last: last.rows[0] || null,
    });
  } catch (err) {
    console.error('Workout summary failed:', err.message);
    res.status(500).json({ error: 'Failed to load workout summary.' });
  }
});

module.exports = router;