"""SQLAlchemy database models."""

from sqlalchemy import Column, Integer, String, Float, Boolean, Text, ForeignKey
from sqlalchemy.orm import relationship
from datetime import datetime

from .database import Base


class Device(Base):
    """Registered surveillance device."""
    __tablename__ = "devices"

    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(String(100), unique=True, index=True, nullable=False)
    device_name = Column(String(200))
    model = Column(String(100))
    os_version = Column(String(50))
    api_key = Column(String(64), unique=True, index=True)
    is_active = Column(Boolean, default=True)
    last_seen = Column(Integer)  # Unix timestamp
    created_at = Column(Integer, default=lambda: int(datetime.now().timestamp()))

    events = relationship("Event", back_populates="device")


class Employee(Base):
    """Employee record."""
    __tablename__ = "employees"

    id = Column(Integer, primary_key=True, index=True)
    employee_id = Column(String(50), unique=True, index=True, nullable=False)
    name = Column(String(200), nullable=False)
    department = Column(String(100))
    email = Column(String(200))
    face_embedding = Column(Text)  # JSON-encoded float array
    is_active = Column(Boolean, default=True)
    created_at = Column(Integer, default=lambda: int(datetime.now().timestamp()))
    updated_at = Column(Integer, default=lambda: int(datetime.now().timestamp()))

    events = relationship("Event", back_populates="employee")
    attendance_records = relationship("Attendance", back_populates="employee")


class Event(Base):
    """Security event record."""
    __tablename__ = "events"

    id = Column(Integer, primary_key=True, index=True)
    event_type = Column(String(50), nullable=False, index=True)
    timestamp = Column(Integer, nullable=False, index=True)  # Unix timestamp
    track_id = Column(Integer)
    device_id = Column(String(100), ForeignKey("devices.device_id"))
    employee_id = Column(String(50), ForeignKey("employees.employee_id"), nullable=True)
    license_plate = Column(String(20))
    duration = Column(Integer, default=0)  # Duration in milliseconds
    confidence = Column(Float, default=0.0)
    extra_data = Column(Text)  # JSON-encoded additional data
    created_at = Column(Integer, default=lambda: int(datetime.now().timestamp()))

    device = relationship("Device", back_populates="events")
    employee = relationship("Employee", back_populates="events")


class Attendance(Base):
    """Employee attendance record."""
    __tablename__ = "attendance"

    id = Column(Integer, primary_key=True, index=True)
    employee_id = Column(String(50), ForeignKey("employees.employee_id"), nullable=False)
    date = Column(String(10), nullable=False, index=True)  # YYYY-MM-DD
    check_in_time = Column(Integer)  # Unix timestamp
    check_out_time = Column(Integer)  # Unix timestamp
    total_duration = Column(Integer, default=0)  # Duration in seconds
    created_at = Column(Integer, default=lambda: int(datetime.now().timestamp()))

    employee = relationship("Employee", back_populates="attendance_records")


class Vehicle(Base):
    """Known vehicle record."""
    __tablename__ = "vehicles"

    id = Column(Integer, primary_key=True, index=True)
    license_plate = Column(String(20), unique=True, index=True, nullable=False)
    vehicle_type = Column(String(50))
    owner_id = Column(String(50))  # Reference to employee or external owner
    owner_name = Column(String(200))
    is_authorized = Column(Boolean, default=False)
    notes = Column(Text)
    created_at = Column(Integer, default=lambda: int(datetime.now().timestamp()))
