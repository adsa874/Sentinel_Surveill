"""Events API router."""

from fastapi import APIRouter, Depends, HTTPException, Header, Query, BackgroundTasks
from sqlalchemy.orm import Session
from typing import List, Optional
from datetime import datetime, timedelta

from ..database import get_db
from ..models import Event, Device, Employee
from ..schemas import (
    BatchEventRequest,
    BatchEventResponse,
    EventResponse
)
from ..websocket import manager
from .. import push

router = APIRouter()


def verify_api_key(
    x_api_key: str = Header(..., alias="X-API-Key"),
    db: Session = Depends(get_db)
) -> Device:
    """Verify API key and return associated device."""
    device = db.query(Device).filter(
        Device.api_key == x_api_key,
        Device.is_active == True
    ).first()

    if not device:
        raise HTTPException(status_code=401, detail="Invalid or inactive API key")

    # Update last seen
    device.last_seen = int(datetime.now().timestamp())
    db.commit()

    return device


@router.post("/events", response_model=BatchEventResponse)
async def create_events(
    request: BatchEventRequest,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    device: Device = Depends(verify_api_key)
):
    """Receive batch of events from device."""
    try:
        events_created = 0
        created_events = []

        for event_data in request.events:
            event = Event(
                event_type=event_data.type,
                timestamp=event_data.timestamp,
                track_id=event_data.track_id,
                device_id=device.device_id,
                employee_id=event_data.employee_id,
                license_plate=event_data.license_plate,
                duration=event_data.duration
            )
            db.add(event)
            db.flush()  # Get the event ID
            created_events.append(event)
            events_created += 1

        db.commit()

        # Broadcast events via WebSocket and send push notifications
        for event in created_events:
            # Get employee name if available
            employee_name = None
            if event.employee_id:
                emp = db.query(Employee).filter(Employee.employee_id == event.employee_id).first()
                if emp:
                    employee_name = emp.name

            event_dict = {
                "id": event.id,
                "event_type": event.event_type,
                "timestamp": event.timestamp,
                "employee_name": employee_name,
                "license_plate": event.license_plate,
                "duration": event.duration
            }

            # Broadcast to WebSocket clients
            background_tasks.add_task(manager.broadcast_event, event_dict)

            # Send push notification for important events
            alert_types = ["UNKNOWN_FACE_DETECTED", "LOITERING_DETECTED"]
            if event.event_type in alert_types:
                details = employee_name or event.license_plate or ""
                background_tasks.add_task(
                    push.send_alert_notification,
                    event.event_type,
                    details,
                    event.id
                )

        return BatchEventResponse(
            success=True,
            processed=events_created,
            message=f"Successfully processed {events_created} events"
        )
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/events", response_model=List[EventResponse])
async def get_events(
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
    event_type: Optional[str] = None,
    start_time: Optional[int] = None,
    end_time: Optional[int] = None,
    db: Session = Depends(get_db)
):
    """Get events with optional filtering."""
    query = db.query(Event)

    if event_type:
        query = query.filter(Event.event_type == event_type)

    if start_time:
        query = query.filter(Event.timestamp >= start_time)

    if end_time:
        query = query.filter(Event.timestamp <= end_time)

    events = query.order_by(Event.timestamp.desc()).offset(offset).limit(limit).all()

    return events


@router.get("/events/today", response_model=List[EventResponse])
async def get_today_events(
    db: Session = Depends(get_db)
):
    """Get all events from today."""
    today_start = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    start_timestamp = int(today_start.timestamp())

    events = db.query(Event).filter(
        Event.timestamp >= start_timestamp
    ).order_by(Event.timestamp.desc()).all()

    return events


@router.get("/events/stats")
async def get_event_stats(
    db: Session = Depends(get_db)
):
    """Get event statistics."""
    today_start = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    start_timestamp = int(today_start.timestamp())

    total_today = db.query(Event).filter(Event.timestamp >= start_timestamp).count()

    people_events = db.query(Event).filter(
        Event.timestamp >= start_timestamp,
        Event.event_type.in_(["PERSON_ENTERED", "PERSON_EXITED", "EMPLOYEE_ARRIVED", "EMPLOYEE_DEPARTED"])
    ).count()

    vehicle_events = db.query(Event).filter(
        Event.timestamp >= start_timestamp,
        Event.event_type.in_(["VEHICLE_ENTERED", "VEHICLE_EXITED"])
    ).count()

    return {
        "total_today": total_today,
        "people_events": people_events,
        "vehicle_events": vehicle_events
    }
