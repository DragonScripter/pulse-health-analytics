CREATE TABLE IF NOT EXISTS heart_rate (
  user_id INT NOT NULL DEFAULT 1,
  time TIMESTAMPTZ NOT NULL,
  bpm INT NOT NULL,
  PRIMARY KEY (user_id, time)
);

-- one row per day: steps, SpO2 
CREATE TABLE IF NOT EXISTS daily_metrics (
  user_id INT NOT NULL DEFAULT 1,
  day DATE NOT NULL,
  steps INT,
  spo2 NUMERIC(4,1),
  updated_at TIMESTAMPTZ DEFAULT now(),
  PRIMARY KEY (user_id, day)
);

-- synced AND manual sleep live here,
CREATE TABLE IF NOT EXISTS sleep_sessions (
  id SERIAL PRIMARY KEY,
  user_id INT NOT NULL DEFAULT 1,
  start_time TIMESTAMPTZ NOT NULL,
  end_time TIMESTAMPTZ NOT NULL,
  source TEXT NOT NULL CHECK (source IN ('health_connect', 'manual')),
  created_at TIMESTAMPTZ DEFAULT now(),
  CHECK (end_time > start_time),
  UNIQUE (user_id, source, start_time)
);
CREATE TABLE IF NOT EXISTS steps_hourly (
  user_id INT NOT NULL DEFAULT 1,
  hour_start TIMESTAMPTZ NOT NULL,
  steps INT NOT NULL,
  PRIMARY KEY (user_id, hour_start)
);