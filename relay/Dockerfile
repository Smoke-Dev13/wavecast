FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends libchromaprint-tools && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY pyproject.toml ./
COPY wavecast ./wavecast
RUN pip install --no-cache-dir ".[ai,redis]"
ENV WAVECAST_PORT=8080 WAVECAST_DB=/data/wavecast.db
RUN mkdir /data && chown nobody /data
VOLUME /data
EXPOSE 8080
USER nobody
HEALTHCHECK --interval=30s --timeout=3s CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8080/healthz')"
CMD ["python", "-u", "-m", "wavecast"]
