"""Dashboard routes for HTML pages."""

from fastapi import APIRouter, Depends, Request, Query
from fastapi.responses import HTMLResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy.orm import Session
from sqlalchemy import func
from pathlib import Path
from datetime import datetime, timedelta

from ..database import get_db
from ..models import Event, Employee, Device, Attendance

router = APIRouter()
templates = Jinja2Templates(directory=str(Path(__file__).parent.parent / "templates"))


def format_timestamp(timestamp: int) -> str:
    """Format Unix timestamp to readable string."""
    if not timestamp:
        return "N/A"
    dt = datetime.fromtimestamp(timestamp)
    return dt.strftime("%H:%M:%S")


def format_date(timestamp: int) -> str:
    """Format Unix timestamp to date string."""
    if not timestamp:
        return "N/A"
    dt = datetime.fromtimestamp(timestamp)
    return dt.strftime("%Y-%m-%d")


def format_duration(ms: int) -> str:
    """Format duration in milliseconds to readable string."""
    if not ms:
        return "0s"
    seconds = ms // 1000
    if seconds < 60:
        return f"{seconds}s"
    minutes = seconds // 60
    if minutes < 60:
        return f"{minutes}m {seconds % 60}s"
    hours = minutes // 60
    return f"{hours}h {minutes % 60}m"


@router.get("/", response_class=HTMLResponse)
async def dashboard(
    request: Request,
    db: Session = Depends(get_db)
):
    """Main dashboard page."""
    today_start = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    start_timestamp = int(today_start.timestamp())

    # Stats
    total_events = db.query(Event).filter(Event.timestamp >= start_timestamp).count()

    people_detected = db.query(Event).filter(
        Event.timestamp >= start_timestamp,
        Event.event_type.in_(["PERSON_ENTERED", "EMPLOYEE_ARRIVED"])
    ).count()

    vehicles_detected = db.query(Event).filter(
        Event.timestamp >= start_timestamp,
        Event.event_type == "VEHICLE_ENTERED"
    ).count()

    # Active devices (seen in last hour)
    hour_ago = int((datetime.now() - timedelta(hours=1)).timestamp())
    active_devices = db.query(Device).filter(
        Device.is_active == True,
        Device.last_seen >= hour_ago
    ).count()

    # Employees present today
    today_str = datetime.now().strftime("%Y-%m-%d")
    employees_present = db.query(Attendance).filter(
        Attendance.date == today_str,
        Attendance.check_in_time.isnot(None),
        Attendance.check_out_time.is_(None)
    ).count()

    # Recent events
    recent_events = db.query(Event).order_by(
        Event.timestamp.desc()
    ).limit(20).all()

    events_with_details = []
    for event in recent_events:
        employee_name = None
        if event.employee_id:
            emp = db.query(Employee).filter(
                Employee.employee_id == event.employee_id
            ).first()
            if emp:
                employee_name = emp.name

        events_with_details.append({
            "id": event.id,
            "event_type": event.event_type,
            "timestamp": event.timestamp,
            "formatted_time": format_timestamp(event.timestamp),
            "employee_name": employee_name,
            "license_plate": event.license_plate,
            "duration": format_duration(event.duration) if event.duration else None
        })

    return templates.TemplateResponse("dashboard.html", {
        "request": request,
        "stats": {
            "total_events": total_events,
            "people_detected": people_detected,
            "vehicles_detected": vehicles_detected,
            "active_devices": active_devices,
            "employees_present": employees_present
        },
        "recent_events": events_with_details,
        "current_date": datetime.now().strftime("%A, %B %d, %Y")
    })


@router.get("/attendance", response_class=HTMLResponse)
async def attendance_page(
    request: Request,
    date: str = Query(None),
    db: Session = Depends(get_db)
):
    """Employee attendance page."""
    if not date:
        date = datetime.now().strftime("%Y-%m-%d")

    # Get all attendance records for the date
    records = db.query(Attendance).filter(
        Attendance.date == date
    ).all()

    attendance_list = []
    for record in records:
        emp = db.query(Employee).filter(
            Employee.employee_id == record.employee_id
        ).first()

        attendance_list.append({
            "employee_id": record.employee_id,
            "employee_name": emp.name if emp else "Unknown",
            "department": emp.department if emp else None,
            "check_in": format_timestamp(record.check_in_time) if record.check_in_time else "Not checked in",
            "check_out": format_timestamp(record.check_out_time) if record.check_out_time else "Still present",
            "duration": format_duration(record.total_duration * 1000) if record.total_duration else "In progress"
        })

    # Get employees who haven't checked in
    checked_in_ids = [r.employee_id for r in records]
    absent = db.query(Employee).filter(
        Employee.is_active == True,
        ~Employee.employee_id.in_(checked_in_ids) if checked_in_ids else True
    ).all()

    return templates.TemplateResponse("attendance.html", {
        "request": request,
        "date": date,
        "formatted_date": datetime.strptime(date, "%Y-%m-%d").strftime("%A, %B %d, %Y"),
        "attendance": attendance_list,
        "absent_employees": [{"id": e.employee_id, "name": e.name, "department": e.department} for e in absent]
    })


@router.get("/events", response_class=HTMLResponse)
async def events_page(
    request: Request,
    event_type: str = Query(None),
    page: int = Query(1, ge=1),
    db: Session = Depends(get_db)
):
    """Events log page."""
    per_page = 50
    offset = (page - 1) * per_page

    query = db.query(Event)

    if event_type:
        query = query.filter(Event.event_type == event_type)

    total = query.count()
    events = query.order_by(Event.timestamp.desc()).offset(offset).limit(per_page).all()

    events_list = []
    for event in events:
        employee_name = None
        if event.employee_id:
            emp = db.query(Employee).filter(
                Employee.employee_id == event.employee_id
            ).first()
            if emp:
                employee_name = emp.name

        events_list.append({
            "id": event.id,
            "event_type": event.event_type,
            "date": format_date(event.timestamp),
            "time": format_timestamp(event.timestamp),
            "employee_name": employee_name,
            "license_plate": event.license_plate,
            "duration": format_duration(event.duration) if event.duration else None,
            "device_id": event.device_id
        })

    # Get event types for filter
    event_types = db.query(Event.event_type).distinct().all()

    return templates.TemplateResponse("events.html", {
        "request": request,
        "events": events_list,
        "event_types": [t[0] for t in event_types],
        "selected_type": event_type,
        "page": page,
        "total_pages": (total + per_page - 1) // per_page,
        "total_events": total
    })


@router.get("/camera", response_class=HTMLResponse)
async def camera_page(request: Request):
    """Web camera surveillance page."""
    return templates.TemplateResponse("camera.html", {"request": request})


@router.get("/devices", response_class=HTMLResponse)
async def devices_page(
    request: Request,
    db: Session = Depends(get_db)
):
    """Devices management page."""
    devices = db.query(Device).order_by(Device.created_at.desc()).all()

    hour_ago = int((datetime.now() - timedelta(hours=1)).timestamp())

    devices_list = []
    for device in devices:
        is_online = device.last_seen and device.last_seen >= hour_ago

        devices_list.append({
            "device_id": device.device_id,
            "device_name": device.device_name,
            "model": device.model,
            "os_version": device.os_version,
            "is_active": device.is_active,
            "is_online": is_online,
            "last_seen": format_timestamp(device.last_seen) if device.last_seen else "Never",
            "last_seen_date": format_date(device.last_seen) if device.last_seen else None
        })

    return templates.TemplateResponse("devices.html", {
        "request": request,
        "devices": devices_list,
        "total_devices": len(devices),
        "online_count": sum(1 for d in devices_list if d["is_online"])
    })
