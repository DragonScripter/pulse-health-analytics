require('dotenv').config();
const express = require('express');
const { Pool } = require('pg');

const app = express();
app.use(express.json({limit: '5mb'})); 

// 1. Postgres connection pool
const pool = new Pool({ connectionString: process.env.DATABASE_URL });

if (!process.env.API_KEY) {
  console.error("API_KEY is not set in the environment variables.");
  process.exit(1);
}

// Health check: confirms the server is up
app.get('/health', (req, res) => res.json({ status: 'ok' }));

//lock for anything under /api

app.use('/api', (req, res, next) => {
  if (req.get('x-api-key') !== process.env.API_KEY) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  next();
});

app.post('/api/metricsData', async (req, res) => {
  console.log("\n==================================================");
  console.log(`RECEIVED PAYLOAD AT: ${new Date().toISOString()}`);
  console.log("==================================================");
  
  // This will print the entire JSON object neatly spaced in your terminal logs
  console.log(JSON.stringify(req.body, null, 2));
  
  console.log("==================================================\n");

  // Send a high-five back to the client
  res.status(200).json({
    status: "intercepted",
    message: "Data structure caught in Node terminal. Inspect logs now!"
  });
})

// NEW ROUTE: Direct database saver for your manual workout entries
app.post('/api/workouts', async (req, res) => {
  console.log("\n==================================================");
  console.log(`WORKOUT SUBMISSION AT: ${new Date().toISOString()}`);
  console.log("==================================================");
  console.log(JSON.stringify(req.body, null, 2));

  const { exercise_name, sets, reps, weight } = req.body;
  const userId = 1; // Temporary hardcoded sandbox profile fallback

  try {
    // Save data parameters right into your PostgreSQL table layer
    await pool.query(
      'INSERT INTO workouts (user_id, exercise_name, sets, reps, weight) VALUES (\$1, \$2, \$3, \$4, \$5)',
      [userId, exercise_name, sets, reps, weight]
    );

    console.log(`[SUCCESS] Saved ${exercise_name} session directly to Postgres!`);
    console.log("==================================================\n");

    // Returns a fallback text recommendation so your tvAiResult text view still updates smoothly
    res.status(200).json({
      message: "Workout tracked successfully",
      ai_analysis: {
        volume_slope: 0.0,
        recommendation: `Saved locally! Automated volume calculation: ${sets * reps * weight} lbs.`
      }
    });

  } catch (err) {
    console.error("Postgres Insertion Failure:", err.message);
    console.log("==================================================\n");
    res.status(500).json({ error: "Failed to persist metrics to database." });
  }
});


app.listen(process.env.PORT || 3000, () => {
  console.log(`Server running on port ${process.env.PORT || 3000}`);
});