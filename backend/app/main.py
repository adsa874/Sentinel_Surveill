"""Main FastAPI application."""

from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from fastapi.middleware.cors import CORSMiddleware
from pathlib import Path
import logging

from .config import get_settings
from .database import init_db
from .routers import events, employees, devices, dashboard
from .websocket import manager
from . import push

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)

settings = get_settings()

# Create FastAPI app
app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
    description="Edge-First Security System Backend"
)

# CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Static files and templates
static_path = Path(__file__).parent / "static"
templates_path = Path(__file__).parent / "templates"

if static_path.exists():
    app.mount("/static", StaticFiles(directory=str(static_path)), name="static")

templates = Jinja2Templates(directory=str(templates_path))

# Include routers
app.include_router(events.router, prefix="/api", tags=["events"])
app.include_router(employees.router, prefix="/api", tags=["employees"])
app.include_router(devices.router, prefix="/api", tags=["devices"])
app.include_router(dashboard.router, tags=["dashboard"])


@app.on_event("startup")
async def startup_event():
    """Initialize database on startup."""
    logger.info("Initializing database...")
    init_db()
    logger.info("Database initialized successfully")


@app.get("/api/health")
async def health_check():
    """Health check endpoint."""
    return {
        "status": "healthy",
        "app": settings.app_name,
        "version": settings.app_version
    }


# WebSocket endpoint for real-time updates
@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    """WebSocket endpoint for real-time event streaming."""
    await manager.connect(websocket)
    try:
        while True:
            # Keep connection alive, listen for client messages
            data = await websocket.receive_text()
            # Handle any client messages if needed
            logger.debug(f"Received WebSocket message: {data}")
    except WebSocketDisconnect:
        await manager.disconnect(websocket)
    except Exception as e:
        logger.error(f"WebSocket error: {e}")
        await manager.disconnect(websocket)


# Push notification endpoints
@app.get("/api/push/vapid-public-key")
async def get_vapid_public_key():
    """Get VAPID public key for push subscriptions."""
    return {"publicKey": push.get_vapid_public_key()}


@app.post("/api/push/subscribe")
async def subscribe_to_push(request: Request):
    """Subscribe to push notifications."""
    subscription_info = await request.json()
    success = push.subscribe(subscription_info)
    return {"success": success}


@app.post("/api/push/unsubscribe")
async def unsubscribe_from_push(request: Request):
    """Unsubscribe from push notifications."""
    data = await request.json()
    endpoint = data.get("endpoint", "")
    success = push.unsubscribe(endpoint)
    return {"success": success}


# WebSocket endpoint for camera frame processing
@app.websocket("/ws/camera")
async def camera_websocket_endpoint(websocket: WebSocket):
    """WebSocket endpoint for processing camera frames from web clients."""
    await websocket.accept()
    logger.info("Camera WebSocket connected")

    try:
        while True:
            data = await websocket.receive_json()

            if data.get("type") == "frame":
                # Process the frame (placeholder for ML detection)
                # In production, this would run actual detection models
                frame_data = data.get("data", "")
                timestamp = data.get("timestamp", 0)
                sensitivity = data.get("sensitivity", 0.5)

                # Placeholder detection response
                # Replace this with actual ML processing
                detections = await process_camera_frame(frame_data, sensitivity)

                # Send detections back to client
                await websocket.send_json({
                    "type": "detections",
                    "detections": detections,
                    "timestamp": timestamp
                })

    except WebSocketDisconnect:
        logger.info("Camera WebSocket disconnected")
    except Exception as e:
        logger.error(f"Camera WebSocket error: {e}")


async def process_camera_frame(frame_data: str, sensitivity: float) -> list:
    """
    Process a camera frame and return detections.

    This is a placeholder that should be replaced with actual ML processing.
    Options for implementation:
    1. Use a Python ML library (OpenCV + YOLO, MediaPipe, etc.)
    2. Forward to a separate ML service
    3. Use cloud-based detection APIs

    Args:
        frame_data: Base64 encoded JPEG image
        sensitivity: Detection sensitivity threshold (0.0 - 1.0)

    Returns:
        List of detection dictionaries with box coordinates and labels
    """
    # Placeholder - returns empty detections
    # To implement real detection, uncomment and modify:
    #
    # import cv2
    # import numpy as np
    # import base64
    #
    # # Decode base64 image
    # img_data = base64.b64decode(frame_data.split(',')[1])
    # nparr = np.frombuffer(img_data, np.uint8)
    # img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    #
    # # Run detection model (e.g., YOLO, MediaPipe)
    # results = model.detect(img)
    #
    # detections = []
    # for r in results:
    #     if r.confidence >= sensitivity:
    #         detections.append({
    #             "type": r.label,
    #             "confidence": r.confidence,
    #             "box": {
    #                 "x": r.x,
    #                 "y": r.y,
    #                 "width": r.width,
    #                 "height": r.height
    #             }
    #         })
    # return detections

    return []
