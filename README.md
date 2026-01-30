# Sentinel - Edge-First Surveillance System

A surveillance system with on-device AI that runs 24/7 on Android, plus a Python/FastAPI backend with a web dashboard.

## Features

- **Android App**: 24/7 foreground service with on-device ML
  - Person and vehicle detection (MediaPipe EfficientDet)
  - Face detection and recognition (MediaPipe Face Landmarker)
  - License plate OCR (ML Kit Text Recognition)
  - Multi-object tracking
  - Event generation (enter/exit, employee attendance, vehicles)
  - Auto-sync to backend

- **Web Dashboard (PWA)**
  - Real-time event monitoring via WebSocket
  - Employee attendance tracking
  - Device management
  - Web-based camera option (works on any device including iPhone)
  - Push notifications
  - Works offline

## Quick Start

### 1. Deploy Backend

**Option A: Railway (Recommended)**
```bash
cd backend
railway init
railway up
```

**Option B: Render**
```bash
# Push to GitHub, then connect repo at render.com
# Uses render.yaml for configuration
```

**Option C: Fly.io**
```bash
cd backend
fly launch --name sentinel-surveillance
fly deploy
```

### 2. Build Android APK

Push to GitHub to trigger the GitHub Actions workflow, then download the APK from the Actions artifacts.

Or build locally:
```bash
cd android
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### 3. Configure Android App

Edit `android/app/src/main/res/values/config.xml`:
```xml
<string name="backend_url">https://your-deployed-backend.railway.app</string>
```

### 4. Install APK

1. Enable "Install unknown apps" in Android settings
2. Download and install the APK
3. Grant camera and notification permissions
4. The surveillance service starts automatically

## Project Structure

```
Sentinel_Surveill/
├── android/                    # Android app
│   └── app/src/main/java/com/sentinel/
│       ├── service/            # Foreground service
│       ├── camera/             # CameraX frame capture
│       ├── ml/                 # MediaPipe ML pipeline
│       ├── tracking/           # Multi-object tracker
│       ├── events/             # Event generation
│       └── network/            # Backend sync
├── backend/
│   └── app/
│       ├── routers/            # API endpoints
│       ├── templates/          # HTML dashboard
│       └── static/             # PWA assets
└── .github/workflows/          # CI/CD
```

## Development

### Backend (Local)
```bash
cd backend
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
python run.py
# Dashboard at http://localhost:8000
```

### Android (Local)
Requires Android SDK and JDK 17.
```bash
cd android
./gradlew assembleDebug
```

## License

MIT
