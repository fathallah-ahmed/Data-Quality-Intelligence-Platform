# Use official Apache Airflow image with Python 3.12
FROM apache/airflow:2.10.0-python3.12

# Switch to root to install additional packages
USER root

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    && rm -rf /var/lib/apt/lists/*

# Switch back to airflow user
USER airflow

# Copy requirements and install additional Python dependencies, pinned to the
# versions Airflow itself was tested with.
ARG AIRFLOW_CONSTRAINTS=https://raw.githubusercontent.com/apache/airflow/constraints-2.10.0/constraints-3.12.txt
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt --constraint "${AIRFLOW_CONSTRAINTS}"

# Copy project files
COPY . /opt/airflow/

# Install the project package so `dq_pipeline` is importable inside containers
RUN pip install --no-cache-dir -e /opt/airflow/

# Default command
CMD ["webserver"]
