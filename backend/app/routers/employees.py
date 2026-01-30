"""Employees API router."""

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from typing import List, Optional
import json
from datetime import datetime

from ..database import get_db
from ..models import Employee, Attendance
from ..schemas import (
    EmployeeCreate,
    EmployeeUpdate,
    EmployeeResponse,
    EmployeeListResponse,
    EmployeeDetail,
    AttendanceRecord
)

router = APIRouter()


@router.get("/employees", response_model=EmployeeListResponse)
async def get_employees(
    active_only: bool = Query(True),
    db: Session = Depends(get_db)
):
    """Get all employees (for device sync)."""
    query = db.query(Employee)

    if active_only:
        query = query.filter(Employee.is_active == True)

    employees = query.order_by(Employee.name).all()

    result = []
    for emp in employees:
        embedding = None
        if emp.face_embedding:
            try:
                embedding = json.loads(emp.face_embedding)
            except (json.JSONDecodeError, TypeError):
                pass

        result.append(EmployeeResponse(
            employee_id=emp.employee_id,
            name=emp.name,
            department=emp.department,
            face_embedding=embedding
        ))

    return EmployeeListResponse(employees=result)


@router.post("/employees", response_model=EmployeeDetail)
async def create_employee(
    employee: EmployeeCreate,
    db: Session = Depends(get_db)
):
    """Create new employee."""
    existing = db.query(Employee).filter(
        Employee.employee_id == employee.employee_id
    ).first()

    if existing:
        raise HTTPException(status_code=400, detail="Employee ID already exists")

    db_employee = Employee(
        employee_id=employee.employee_id,
        name=employee.name,
        department=employee.department,
        email=employee.email
    )

    db.add(db_employee)
    db.commit()
    db.refresh(db_employee)

    return db_employee


@router.get("/employees/{employee_id}", response_model=EmployeeDetail)
async def get_employee(
    employee_id: str,
    db: Session = Depends(get_db)
):
    """Get employee by ID."""
    employee = db.query(Employee).filter(
        Employee.employee_id == employee_id
    ).first()

    if not employee:
        raise HTTPException(status_code=404, detail="Employee not found")

    return employee


@router.put("/employees/{employee_id}", response_model=EmployeeDetail)
async def update_employee(
    employee_id: str,
    update: EmployeeUpdate,
    db: Session = Depends(get_db)
):
    """Update employee details."""
    employee = db.query(Employee).filter(
        Employee.employee_id == employee_id
    ).first()

    if not employee:
        raise HTTPException(status_code=404, detail="Employee not found")

    if update.name is not None:
        employee.name = update.name
    if update.department is not None:
        employee.department = update.department
    if update.email is not None:
        employee.email = update.email
    if update.is_active is not None:
        employee.is_active = update.is_active

    employee.updated_at = int(datetime.now().timestamp())

    db.commit()
    db.refresh(employee)

    return employee


@router.delete("/employees/{employee_id}")
async def delete_employee(
    employee_id: str,
    db: Session = Depends(get_db)
):
    """Delete (deactivate) employee."""
    employee = db.query(Employee).filter(
        Employee.employee_id == employee_id
    ).first()

    if not employee:
        raise HTTPException(status_code=404, detail="Employee not found")

    employee.is_active = False
    employee.updated_at = int(datetime.now().timestamp())
    db.commit()

    return {"success": True, "message": "Employee deactivated"}


@router.post("/employees/{employee_id}/embedding")
async def update_embedding(
    employee_id: str,
    embedding: List[float],
    db: Session = Depends(get_db)
):
    """Update employee face embedding."""
    employee = db.query(Employee).filter(
        Employee.employee_id == employee_id
    ).first()

    if not employee:
        raise HTTPException(status_code=404, detail="Employee not found")

    employee.face_embedding = json.dumps(embedding)
    employee.updated_at = int(datetime.now().timestamp())
    db.commit()

    return {"success": True, "message": "Embedding updated"}


@router.get("/employees/{employee_id}/attendance", response_model=List[AttendanceRecord])
async def get_employee_attendance(
    employee_id: str,
    limit: int = Query(30, ge=1, le=365),
    db: Session = Depends(get_db)
):
    """Get employee attendance history."""
    employee = db.query(Employee).filter(
        Employee.employee_id == employee_id
    ).first()

    if not employee:
        raise HTTPException(status_code=404, detail="Employee not found")

    records = db.query(Attendance).filter(
        Attendance.employee_id == employee_id
    ).order_by(Attendance.date.desc()).limit(limit).all()

    return [
        AttendanceRecord(
            employee_id=r.employee_id,
            employee_name=employee.name,
            date=r.date,
            check_in_time=r.check_in_time,
            check_out_time=r.check_out_time,
            total_duration=r.total_duration
        )
        for r in records
    ]
