const {
  EMAIL_REGEX,
  MAX_EMAIL_LENGTH,
  MIN_PASSWORD_LENGTH,
  MAX_PASSWORD_BYTES,
  BCRYPT_SALT_ROUNDS,
} = require('../constants');
const express = require('express');
const bcrypt = require('bcrypt');
const rateLimit = require('express-rate-limit');
const pool = require('../db');
const router = express.Router();

const signupLimiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15 minutes
  limit: 10, // 10 attempts per IP per window
  message: { error: 'Too many attempts. Try again later.' },
});

router.post('/signup', signupLimiter, async (req, res) => {
  const { email, password } = req.body;

  // 1. Basic validation
  if (typeof email !== 'string' || typeof password !== 'string') {
    return res
      .status(400)
      .json({ error: 'Email and password are required.' });
  }

  const cleanEmail = email.trim().toLowerCase();

  if (cleanEmail.length > MAX_EMAIL_LENGTH || !EMAIL_REGEX.test(cleanEmail)) {
    return res
      .status(400)
      .json({ error: 'Please enter a valid email address.' });
  }

  if (password.length < MIN_PASSWORD_LENGTH) {
    return res
      .status(400)
      .json({
        error: `Password must be at least ${MIN_PASSWORD_LENGTH} characters.`,
      });
  }

  if (Buffer.byteLength(password, 'utf8') > MAX_PASSWORD_BYTES) {
    return res
      .status(400)
      .json({
        error: `Password is too long (max '${MAX_PASSWORD_BYTES}' bytes).`,
      });
  }

  try {
    // 2. Check if the email is already registered
    const existing = await pool.query('SELECT user_id FROM users WHERE email = $1', [
      cleanEmail,
    ]);
    if (existing.rows.length > 0) {
      return res.status(400).json({ error: 'Unable to create account with that email.' });
    }

    // 3. Hash the password (10 salt rounds)
    const passwordHash = await bcrypt.hash(password, BCRYPT_SALT_ROUNDS);

    // 4. Insert the user
    await pool.query(
      'INSERT INTO users (email, password_hash, created_at) VALUES ($1, $2, NOW())',
      [cleanEmail, passwordHash],
    );

    return res.status(201).json({ message: 'Account created.' });
  } catch (err) {
    // Race condition: two requests passed the SELECT at the same time
    if (err.code === '23505') {
      return res.status(400).json({ error: 'Unable to create account with that email.' });
    }
    console.error('Signup failure:', err.message);
    return res.status(500).json({ error: 'Something went wrong.' });
  }
});

module.exports = router;
