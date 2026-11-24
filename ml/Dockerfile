FROM python:3.12-slim
WORKDIR /app
COPY ml/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --system --uid 10001 model
COPY ml ml
USER model
ENV OPENBLAS_NUM_THREADS=2 OMP_NUM_THREADS=2 PYTHONDONTWRITEBYTECODE=1
EXPOSE 8000
CMD ["uvicorn","ml.app:app","--host","0.0.0.0","--port","8000"]
