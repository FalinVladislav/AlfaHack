FROM python:3.14-slim
WORKDIR /app
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 INFERENCE_HOST=0.0.0.0 INFERENCE_PORT=8090
COPY server.py openapi.json ./
USER 10001:10001
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD ["python", "-c", "from urllib.request import urlopen; urlopen('http://127.0.0.1:8090/health', timeout=2)"]
CMD ["python", "server.py"]
