"""Pydantic schemas for request/response validation."""

from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime


# Device schemas
class DeviceRegistration(BaseModel):
    device_id: str
    device_name: str
    model: str
    os_version: str


class DeviceRegistrationResponse(BaseModel):
    success: bool
    api_key: str
    message: Optional[str] = None


class DeviceInfo(BaseModel):
    device_id: str
    device_name: str
    model: str
    is_active: bool
    last_seen: Optional[int] = None

    class Config:
        from_attributes = True


# Event schemas
class EventCreate(BaseModel):
    type: str
    timestamp: int
    track_id: int
    employee_id: Optional[str] = None
    license_plate: Optional[str] = None
    duration: int = 0
    device_id: str


class BatchEventRequest(BaseModel):
    events: List[EventCreate]
    device_id: str


class BatchEventResponse(BaseModel):
    success: bool
    processed: int
    message: Optional[str] = None


class EventResponse(BaseModel):
    id: int
    event_type: str
    timestamp: int
    track_id: Optional[int] = None
    device_id: Optional[str] = None
    employee_id: Optional[str] = None
    license_plate: Optional[str] = None
    duration: int = 0

    class Config:
        from_attributes = True


# Employee schemas
class EmployeeCreate(BaseModel):
    employee_id: str
    name: str
    department: Optional[str] = None
    email: Optional[str] = None


class EmployeeUpdate(BaseModel):
    name: Optional[str] = None
    department: Optional[str] = None
    email: Optional[str] = None
    is_active: Optional[bool] = None


class EmployeeResponse(BaseModel):
    employee_id: str
    name: str
    department: Optional[str] = None
    face_embedding: Optional[List[float]] = None

    class Config:
        from_attributes = True


class EmployeeListResponse(BaseModel):
    employees: List[EmployeeResponse]


class EmployeeDetail(BaseModel):
    employee_id: str
    name: str
    department: Optional[str] = None
    email: Optional[str] = None
    is_active: bool
    created_at: int

    class Config:
        from_attributes = True


# Attendance schemas
class AttendanceRecord(BaseModel):
    employee_id: str
    employee_name: str
    date: str
    check_in_time: Optional[int] = None
    check_out_time: Optional[int] = None
    total_duration: int = 0

    class Config:
        from_attributes = True


# Dashboard schemas
class DashboardStats(BaseModel):
    total_events_today: int
    people_detected_today: int
    vehicles_detected_today: int
    employees_present: int
    active_devices: int


class RecentEvent(BaseModel):
    id: int
    event_type: str
    timestamp: int
    employee_name: Optional[str] = None
    license_plate: Optional[str] = None
    formatted_time: str
