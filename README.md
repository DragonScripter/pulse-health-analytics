# pulse-health-analytics

A personal health tracking app I'm putting together because my smartwatch (USMECBL) locks its data away, and I wanted a way to actually use my own biometric info. It syncs watch data into Android, sends it to a server, and lets me connect with friends/family(eventually) to share stats.

I'm also using this to screw around with some Machine Learning stuff for an AI class I'm taking before graduating.

## The Stack 
* **Frontend:** A native Android App written in **Kotlin** (Jetpack Compose). It hooks into **Android Health Connect** to pull steps, heart rate, and sleep data out of the phone.
* **Main Backend:** **Node.js (Express)**. I'm comfortable with Node, so this handles the easy stuff: user logins, saving data to **PostgreSQL**, and managing the friends list(later).
* **AI Service:** A small **Python** container. I didn't want to write a full backend in Python, so Node just passes arrays of data to this Python container whenever I want to calculate a linear regression trend line (like 7-day weight velocity or heart rate changes over time).
* **Environment:** **Docker Compose** to spin up Node, Python, and Postgres locally.

## Folder Layout
```text
pulse-health-analystics/
├── android/       # The Kotlin Android App
├── backend/      # Node.js Express server (The middleman)
├── ai-service/           # Python script for Linear Regression math
└── docker-compose.yml    # Pins the backend containers together
```

## Data Flow for now
1. Watch sends data to its crappy app over Bluetooth.
2. Crappy app syncs it locally into Android **Health Connect**.
3. My App reads it from Health Connect and hits a Node.js endpoint with a massive JSON payload.
4. **Node.js** saves it to Postgres.
5. When I want an AI trend check, Node forwards the history to **Python** via an internal HTTP request.
6. Python calculates the slope ($y = mx + b$) using Scikit-Learn and tells back to Node if my health metrics are trending down or looking good.

