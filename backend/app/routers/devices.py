"""Devices API router."""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from typing import List
import secrets
from datetime import datetime

from ..database import get_db
from ..models import Device
from ..schemas import (
    DeviceRegistration,
    DeviceRegistrationResponse,
    DeviceInfo
)

router = APIRouter()


def generate_api_key() -> str:
    """Generate a secure API key."""
    return secrets.token_urlsafe(32)


@router.post("/devices/register", response_model=DeviceRegistrationResponse)
async def register_device(
    registration: DeviceRegistration,
    db: Session = Depends(get_db)
):
    """Register a new device or update existing."""
    existing = db.query(Device).filter(
        Device.device_id == registration.device_id
    ).first()

    if existing:
        # Update existing device
        existing.device_name = registration.device_name
        existing.model = registration.model
        existing.os_version = registration.os_version
        existing.is_active = True
        existing.last_seen = int(datetime.now().timestamp())

        db.commit()

        return DeviceRegistrationResponse(
            success=True,
            api_key=existing.api_key,
            message="Device updated successfully"
        )

    # Create new device
    api_key = generate_api_key()

    device = Device(
        device_id=registration.device_id,
        device_name=registration.device_name,
        model=registration.model,
        os_version=registration.os_version,
        api_key=api_key,
        last_seen=int(datetime.now().timestamp())
    )

    db.add(device)
    db.commit()

    return DeviceRegistrationResponse(
        success=True,
        api_key=api_key,
        message="Device registered successfully"
    )


@router.get("/devices", response_model=List[DeviceInfo])
async def get_devices(
    db: Session = Depends(get_db)
):
    """Get all registered devices."""
    devices = db.query(Device).order_by(Device.created_at.desc()).all()

    return [
        DeviceInfo(
            device_id=d.device_id,
            device_name=d.device_name,
            model=d.model,
            is_active=d.is_active,
            last_seen=d.last_seen
        )
        for d in devices
    ]


@router.get("/devices/{device_id}", response_model=DeviceInfo)
async def get_device(
    device_id: str,
    db: Session = Depends(get_db)
):
    """Get device by ID."""
    device = db.query(Device).filter(
        Device.device_id == device_id
    ).first()

    if not device:
        raise HTTPException(status_code=404, detail="Device not found")

    return DeviceInfo(
        device_id=device.device_id,
        device_name=device.device_name,
        model=device.model,
        is_active=device.is_active,
        last_seen=device.last_seen
    )


@router.put("/devices/{device_id}/deactivate")
async def deactivate_device(
    device_id: str,
    db: Session = Depends(get_db)
):
    """Deactivate a device."""
    device = db.query(Device).filter(
        Device.device_id == device_id
    ).first()

    if not device:
        raise HTTPException(status_code=404, detail="Device not found")

    device.is_active = False
    db.commit()

    return {"success": True, "message": "Device deactivated"}


@router.put("/devices/{device_id}/activate")
async def activate_device(
    device_id: str,
    db: Session = Depends(get_db)
):
    """Activate a device."""
    device = db.query(Device).filter(
        Device.device_id == device_id
    ).first()

    if not device:
        raise HTTPException(status_code=404, detail="Device not found")

    device.is_active = True
    db.commit()

    return {"success": True, "message": "Device activated"}
