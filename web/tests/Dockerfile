FROM mcr.microsoft.com/playwright:v1.56.1-noble
RUN apt-get update && apt-get install -y --no-install-recommends python3-venv && rm -rf /var/lib/apt/lists/*
COPY backend/requirements.txt /tmp/requirements.txt
RUN python3 -m venv /opt/witness && /opt/witness/bin/pip install --no-cache-dir -r /tmp/requirements.txt
ENV E2E_PYTHON=/opt/witness/bin/python
WORKDIR /work
CMD ["node", "web/tests/e2e.mjs"]
