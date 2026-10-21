require('dotenv').config();
const express = require('express');
const requireApiKey = require('./middleware/apiKey');

if (!process.env.API_KEY || !process.env.JWT_SECRET) {
  console.error('API_KEY and JWT_SECRET must be set.');
  process.exit(1);
}

const app = express();
app.use(express.json({ limit: '5mb' }));

// Public
app.get('/health', (req, res) => res.json({ status: 'ok' }));
app.use('/api/auth', require('./routes/auth'));     
// Everything under /api requires the key
app.use('/api', requireApiKey, require('./routes'));

app.listen(process.env.PORT || 3000, () => {
  console.log(`Server running on port ${process.env.PORT || 3000}`);
});
