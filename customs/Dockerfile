FROM python:3.13-slim

# A font with Greek glyphs for the PDF record.
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY customsdesk customsdesk
COPY data data
COPY samples samples

# Database, accounts and uploaded documents live here. Mount a persistent disk at /data.
ENV CUSTOMSDESK_DATA=/data
EXPOSE 8000
CMD ["sh", "-c", "exec uvicorn --factory customsdesk.api:create_app --host 0.0.0.0 --port ${PORT:-8000}"]
