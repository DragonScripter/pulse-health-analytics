const express = require('express');
const router = express.Router();

router.use('/metricsData', require('./metrics'));
router.use('/workouts', require('./workouts'));
router.use('/watch-metrics', require('./watch'));
router.use('/sleep', require('./sleep'));
module.exports = router;
