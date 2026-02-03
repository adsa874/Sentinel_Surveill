"""Push notification service."""

import json
import logging
from typing import Optional, Dict, Any
from pathlib import Path

from pywebpush import webpush, WebPushException
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.backends import default_backend
import base64

from .config import get_settings

logger = logging.getLogger(__name__)
settings = get_settings()

# Store for push subscriptions (in production, use database)
push_subscriptions: Dict[str, Dict[str, Any]] = {}

# VAPID keys path (use /tmp for Cloud Run compatibility)
VAPID_PRIVATE_KEY_PATH = Path("/tmp/vapid_private.pem")
VAPID_PUBLIC_KEY_PATH = Path("/tmp/vapid_public.txt")


def generate_vapid_keys():
    """Generate VAPID key pair if not exists."""
    if VAPID_PRIVATE_KEY_PATH.exists() and VAPID_PUBLIC_KEY_PATH.exists():
        return

    # Generate EC key pair
    private_key = ec.generate_private_key(ec.SECP256R1(), default_backend())

    # Save private key
    private_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption()
    )
    VAPID_PRIVATE_KEY_PATH.write_bytes(private_pem)

    # Get public key in uncompressed format and base64url encode
    public_key = private_key.public_key()
    public_numbers = public_key.public_numbers()

    # Create uncompressed point format (0x04 + x + y)
    x_bytes = public_numbers.x.to_bytes(32, byteorder='big')
    y_bytes = public_numbers.y.to_bytes(32, byteorder='big')
    uncompressed = b'\x04' + x_bytes + y_bytes

    public_key_b64 = base64.urlsafe_b64encode(uncompressed).decode('utf-8').rstrip('=')
    VAPID_PUBLIC_KEY_PATH.write_text(public_key_b64)

    logger.info("Generated new VAPID keys")


def get_vapid_public_key() -> str:
    """Get the VAPID public key."""
    generate_vapid_keys()
    return VAPID_PUBLIC_KEY_PATH.read_text().strip()


def get_vapid_private_key() -> str:
    """Get the VAPID private key."""
    generate_vapid_keys()
    return VAPID_PRIVATE_KEY_PATH.read_text().strip()


def subscribe(subscription_info: Dict[str, Any], user_id: str = "anonymous") -> bool:
    """Store a push subscription."""
    try:
        endpoint = subscription_info.get("endpoint", "")
        push_subscriptions[endpoint] = {
            "subscription": subscription_info,
            "user_id": user_id
        }
        logger.info(f"Push subscription added for {user_id}")
        return True
    except Exception as e:
        logger.error(f"Failed to store subscription: {e}")
        return False


def unsubscribe(endpoint: str) -> bool:
    """Remove a push subscription."""
    if endpoint in push_subscriptions:
        del push_subscriptions[endpoint]
        logger.info(f"Push subscription removed: {endpoint[:50]}...")
        return True
    return False


async def send_push_notification(
    title: str,
    body: str,
    url: str = "/",
    tag: str = "sentinel-alert",
    event_id: Optional[int] = None
):
    """Send push notification to all subscribers."""
    if not push_subscriptions:
        return

    data = json.dumps({
        "title": title,
        "body": body,
        "url": url,
        "tag": tag,
        "eventId": event_id
    })

    vapid_claims = {
        "sub": f"mailto:admin@{settings.host}"
    }

    failed_endpoints = []

    for endpoint, sub_data in push_subscriptions.items():
        try:
            webpush(
                subscription_info=sub_data["subscription"],
                data=data,
                vapid_private_key=get_vapid_private_key(),
                vapid_claims=vapid_claims
            )
            logger.debug(f"Push sent to {endpoint[:50]}...")
        except WebPushException as e:
            logger.error(f"Push failed: {e}")
            if e.response and e.response.status_code in (404, 410):
                # Subscription expired or invalid
                failed_endpoints.append(endpoint)
        except Exception as e:
            logger.error(f"Push error: {e}")

    # Clean up invalid subscriptions
    for endpoint in failed_endpoints:
        unsubscribe(endpoint)


async def send_alert_notification(event_type: str, details: str = "", event_id: int = None):
    """Send alert notification for security events."""
    alert_types = {
        "UNKNOWN_FACE_DETECTED": ("Unknown Person Detected", "An unrecognized face was detected"),
        "LOITERING_DETECTED": ("Loitering Alert", "Unusual activity detected"),
        "VEHICLE_ENTERED": ("Vehicle Entered", "A vehicle has entered the premises"),
    }

    if event_type in alert_types:
        title, default_body = alert_types[event_type]
        body = details or default_body
        await send_push_notification(
            title=title,
            body=body,
            url=f"/events?highlight={event_id}" if event_id else "/events",
            tag=f"alert-{event_type.lower()}",
            event_id=event_id
        )
