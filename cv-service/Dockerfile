FROM python:3.11-slim

WORKDIR /app

# System deps required by OpenCV / Pillow / scikit-image
RUN apt-get update && apt-get install -y \
    libgl1 \
    libglib2.0-0 \
    libsm6 \
    libxext6 \
    curl \
    && rm -rf /var/lib/apt/lists/*

# ── Step 1: CPU-only PyTorch (separate index URL; avoids pulling CUDA wheels) ─
# Install before the rest of requirements so Docker caches this heavy layer.
RUN pip install --no-cache-dir \
    torch==2.3.1 \
    torchvision==0.18.1 \
    --index-url https://download.pytorch.org/whl/cpu

# ── Step 2: Everything else (skip torch/torchvision, already installed) ───────
COPY requirements.txt .
RUN pip install --no-cache-dir \
    $(grep -v '^#' requirements.txt | grep -v '^torch' | grep -v '^torchvision' | grep -v '^\s*$')

# ── Application ────────────────────────────────────────────────────────────────
COPY app/ ./app/
COPY tests/ ./tests/

# Model weights are NOT baked into the image (keeps the image small). They are
# downloaded on FIRST container startup into HF_HOME, which docker-compose
# mounts to the persistent `model_cache` named volume. Subsequent container
# recreations reuse the cached weights — no re-download.
ENV HF_HOME=/model-cache
ENV GROUNDING_DINO_MODEL=IDEA-Research/grounding-dino-base

ENV PYTHONPATH=/app
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
# Default: never fabricate detections.
ENV CV_ALLOW_MOCK=false

EXPOSE 8001

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001", "--workers", "1"]
