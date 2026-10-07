require('dotenv').config();
const express = require('express');
const { Pool } = require('pg');

const app = express();
app.use(express.json());

// 1. Postgres connection pool
const pool = new Pool({ connectionString: process.env.DATABASE_URL });

// Health check: confirms the server is up
app.get('/health', (req, res) => res.json({ status: 'ok' }));

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


app.listen(process.env.PORT || 3000, () => {
  console.log(`Server running on port ${process.env.PORT || 3000}`);
});