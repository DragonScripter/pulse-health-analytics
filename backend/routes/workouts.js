const express = require('express');
const pool = require('../db');
const router = express.Router();

router.post('/', async (req, res) => {
  console.log(`WORKOUT SUBMISSION AT: ${new Date().toISOString()}`);
  console.log(JSON.stringify(req.body, null, 2));

  const { exercise_name, sets, reps, weight } = req.body;
  const userId = req.user.user_id;; // Temporary hardcoded sandbox profile

  try {
    await pool.query(
      'INSERT INTO workouts (user_id, exercise_name, sets, reps, weight) VALUES ($1, $2, $3, $4, $5)',
      [userId, exercise_name, sets, reps, weight],
    );

    console.log(`[SUCCESS] Saved ${exercise_name} session to Postgres`);

    res.status(200).json({
      message: 'Workout tracked successfully',
      ai_analysis: {
        volume_slope: 0.0,
        recommendation: `Saved locally! Automated volume calculation: ${sets * reps * weight} lbs.`,
      },
    });
  } catch (err) {
    console.error('Postgres Insertion Failure:', err.message);
    res.status(500).json({ error: 'Failed to persist metrics to database.' });
  }
});

module.exports = router;
