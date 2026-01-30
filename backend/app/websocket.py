"""WebSocket manager for real-time updates."""

from fastapi import WebSocket
from typing import Set, Dict, Any
import json
import asyncio
import logging

logger = logging.getLogger(__name__)


class ConnectionManager:
    """Manages WebSocket connections for real-time updates."""

    def __init__(self):
        self.active_connections: Set[WebSocket] = set()
        self._lock = asyncio.Lock()

    async def connect(self, websocket: WebSocket):
        """Accept and store a new WebSocket connection."""
        await websocket.accept()
        async with self._lock:
            self.active_connections.add(websocket)
        logger.info(f"WebSocket connected. Total connections: {len(self.active_connections)}")

    async def disconnect(self, websocket: WebSocket):
        """Remove a WebSocket connection."""
        async with self._lock:
            self.active_connections.discard(websocket)
        logger.info(f"WebSocket disconnected. Total connections: {len(self.active_connections)}")

    async def broadcast(self, message: Dict[str, Any]):
        """Broadcast a message to all connected clients."""
        if not self.active_connections:
            return

        message_json = json.dumps(message)
        disconnected = set()

        async with self._lock:
            for connection in self.active_connections:
                try:
                    await connection.send_text(message_json)
                except Exception as e:
                    logger.error(f"Error sending to WebSocket: {e}")
                    disconnected.add(connection)

            # Remove disconnected clients
            self.active_connections -= disconnected

    async def send_personal(self, websocket: WebSocket, message: Dict[str, Any]):
        """Send a message to a specific client."""
        try:
            await websocket.send_text(json.dumps(message))
        except Exception as e:
            logger.error(f"Error sending personal message: {e}")

    async def broadcast_event(self, event: Dict[str, Any], stats: Dict[str, Any] = None):
        """Broadcast a new event to all clients."""
        await self.broadcast({
            "type": "new_event",
            "event": event,
            "stats": stats
        })

    async def broadcast_stats(self, stats: Dict[str, Any]):
        """Broadcast updated stats to all clients."""
        await self.broadcast({
            "type": "stats_update",
            "stats": stats
        })

    async def broadcast_device_status(self, device: Dict[str, Any]):
        """Broadcast device status change."""
        await self.broadcast({
            "type": "device_status",
            "device": device
        })


# Global connection manager instance
manager = ConnectionManager()
