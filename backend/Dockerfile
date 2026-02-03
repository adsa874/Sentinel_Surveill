# Sentinel Backend Dockerfile
FROM python:3.11-slim

WORKDIR /app

# Install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY . .

# Set environment variables (use /tmp for Cloud Run writable filesystem)
ENV DATABASE_URL=sqlite:////tmp/sentinel.db
ENV PORT=8000

# Expose port
EXPOSE 8000

# Run the application (use PORT env var for Cloud Run)
CMD uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}
