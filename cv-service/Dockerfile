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
# Install before the rest of requirements so Docker can cache this heavy layer.
RUN pip install --no-cache-dir \
    torch==2.3.1 \
    torchvision==0.18.1 \
    --index-url https://download.pytorch.org/whl/cpu

# ── Step 2: Everything else ────────────────────────────────────────────────────
COPY requirements.txt .
# torch/torchvision are already installed; skip them on the second pass.
RUN pip install --no-cache-dir \
    $(grep -v '^#' requirements.txt | grep -v '^torch' | grep -v '^torchvision' | grep -v '^\s*$')

# ── Step 3: Pre-download Grounding-DINO weights ───────────────────────────────
# HF_HOME is set to /model-cache so the weights layer can be cached on a
# Docker volume (mount -v vastra_model_cache:/model-cache) and survive
# container rebuilds without re-downloading ~700 MB.
ENV HF_HOME=/model-cache
ENV GROUNDING_DINO_MODEL=IDEA-Research/grounding-dino-base

# Download weights during build when network is available.
# If the build runs without internet (e.g. CI), this step is skipped and
# the model will be downloaded on first container startup instead.
RUN python - <<'EOF' || echo "Model pre-download skipped (no network at build time)"
import os
os.environ.setdefault("HF_HOME", "/model-cache")
try:
    from transformers import AutoProcessor, AutoModelForZeroShotObjectDetection
    model_id = os.environ["GROUNDING_DINO_MODEL"]
    print(f"Pre-downloading {model_id} …")
    AutoProcessor.from_pretrained(model_id)
    AutoModelForZeroShotObjectDetection.from_pretrained(model_id)
    print("Pre-download complete.")
except Exception as e:
    print(f"Pre-download failed: {e}")
    raise
EOF

# ── Application ────────────────────────────────────────────────────────────────
COPY app/ ./app/

ENV PYTHONPATH=/app
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
# Default: never fabricate detections.
ENV CV_ALLOW_MOCK=false

EXPOSE 8001

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001", "--workers", "1"]
