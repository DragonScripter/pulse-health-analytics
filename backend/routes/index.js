const express = require('express');
const router = express.Router();

router.use('/metricsData', require('./metrics'));
router.use('/workouts', require('./workouts'));

module.exports = router;