require('dotenv').config();
const express = require('express');
const requireAuth = require('./middleware/auth');
if (!process.env.JWT_SECRET) {
  console.error('JWT_SECRET must be set.');
  process.exit(1);
}

const app = express();
app.use(express.json({ limit: '5mb' }));

// Public
app.get('/health', (req, res) => res.json({ status: 'ok' }));
app.use('/api/auth', require('./routes/auth'));     
// Everything under /api is protected
app.use('/api', requireAuth, require('./routes')); // protected

app.listen(process.env.PORT || 3000, () => {
  console.log(`Server running on port ${process.env.PORT || 3000}`);
});
