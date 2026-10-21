module.exports = function requireApiKey(req, res, next) {
  if (req.get('x-api-key') !== process.env.API_KEY) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  next();
};
