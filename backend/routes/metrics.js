const express = require('express');
const router = express.Router();

router.post('/', (req, res) => {
  console.log(`RECEIVED PAYLOAD AT: ${new Date().toISOString()}`);
  console.log(JSON.stringify(req.body, null, 2));

  res.status(200).json({
    status: 'intercepted',
    message: 'Data structure caught in Node terminal. Inspect logs now!',
  });
});

module.exports = router;
