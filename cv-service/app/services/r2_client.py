import io
import os
import uuid
import logging
import boto3
from botocore.config import Config
from botocore.exceptions import ClientError, EndpointConnectionError
from PIL import Image

logger = logging.getLogger(__name__)


class R2DownloadError(Exception):
    """Raised when an R2 object cannot be fetched. `kind` distinguishes the cause
    so callers can return an accurate HTTP status / message instead of a blanket
    404 'image not found'."""
    def __init__(self, kind: str, message: str):
        super().__init__(message)
        self.kind = kind  # "not_found" | "auth" | "network" | "not_configured" | "unknown"


class R2Client:
    def __init__(self):
        self.bucket = os.getenv("R2_BUCKET", "vastra-media")
        endpoint = os.getenv("R2_ENDPOINT", "")
        access_key = os.getenv("R2_ACCESS_KEY", "")
        secret_key = os.getenv("R2_SECRET_KEY", "")

        if endpoint and access_key and secret_key:
            self.client = boto3.client(
                "s3",
                endpoint_url=endpoint,
                aws_access_key_id=access_key,
                aws_secret_access_key=secret_key,
                config=Config(signature_version="s3v4"),
            )
            self.configured = True
            logger.info(f"R2Client configured: bucket={self.bucket} endpoint={endpoint}")
        else:
            self.client = None
            self.configured = False
            logger.warning(
                "R2Client NOT configured (missing R2_ENDPOINT/R2_ACCESS_KEY/R2_SECRET_KEY) "
                "— image downloads will fail."
            )

    def download_image(self, key: str) -> Image.Image:
        """
        Fetch an object from R2 and return it as an RGB PIL image.

        Raises R2DownloadError with a specific `kind` so the caller can return an
        accurate status. Previously every failure (auth, network, missing key,
        wrong bucket) surfaced as a misleading '404 image not found'.
        """
        if not self.configured:
            raise R2DownloadError(
                "not_configured",
                f"R2 not configured on the CV service; cannot fetch '{key}'. "
                "Check R2_ENDPOINT/R2_ACCESS_KEY/R2_SECRET_KEY/R2_BUCKET match the backend.",
            )
        buf = io.BytesIO()
        try:
            self.client.download_fileobj(self.bucket, key, buf)
        except ClientError as e:
            code = e.response.get("Error", {}).get("Code", "")
            if code in ("404", "NoSuchKey", "NoSuchBucket"):
                logger.error(f"R2 object missing: bucket={self.bucket} key={key} code={code}")
                raise R2DownloadError("not_found",
                    f"Object not found in R2: bucket={self.bucket} key={key} (code={code})")
            if code in ("403", "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch"):
                logger.error(f"R2 access denied: bucket={self.bucket} key={key} code={code}")
                raise R2DownloadError("auth",
                    f"R2 access denied for bucket={self.bucket} (code={code}). Check credentials.")
            logger.error(f"R2 client error: bucket={self.bucket} key={key} code={code} err={e}")
            raise R2DownloadError("unknown", f"R2 client error (code={code}): {e}")
        except EndpointConnectionError as e:
            logger.error(f"R2 endpoint unreachable: {e}")
            raise R2DownloadError("network", f"Could not reach R2 endpoint: {e}")
        except Exception as e:
            logger.error(f"R2 download failed (unexpected): bucket={self.bucket} key={key} err={e}")
            raise R2DownloadError("unknown", f"Unexpected R2 download error: {e}")
        buf.seek(0)
        raw = buf.read()
        buf.seek(0)

        # Inspect magic bytes to detect non-image responses (XML/HTML error pages, etc.)
        magic = raw[:16] if len(raw) >= 16 else raw
        is_jpeg = raw[:2] == b'\xff\xd8'
        is_png  = raw[:8] == b'\x89PNG\r\n\x1a\n'
        is_webp = raw[:4] == b'RIFF' and raw[8:12] == b'WEBP'
        is_gif  = raw[:6] in (b'GIF87a', b'GIF89a')
        # HEIC/HEIF: 4-byte box size + b'ftyp' + brand (heic/heif/mif1/msf1)
        is_heic = (len(raw) >= 12 and raw[4:8] == b'ftyp'
                   and raw[8:12] in (b'heic', b'heif', b'mif1', b'msf1', b'heix', b'avif'))
        is_image = is_jpeg or is_png or is_webp or is_gif

        if not is_image:
            head_bytes  = raw[:128]
            head_text   = head_bytes.decode("utf-8", errors="replace")
            hex_preview = magic.hex()

            if is_heic:
                brand = raw[8:12].decode("ascii", errors="replace")
                detail = (
                    f"R2 key='{key}' contains HEIC/HEIF data (ftyp brand='{brand}') "
                    f"but was stored with a .jpg key. "
                    "The Android app must normalize images to JPEG before upload."
                )
            elif head_text.lstrip().startswith("<"):
                detail = (
                    f"R2 key='{key}' returned an XML/HTML error page ({len(raw)} bytes). "
                    "Likely an expired presigned URL or a bucket permission error. "
                    f"Preview: {head_text[:200]!r}"
                )
            else:
                detail = (
                    f"R2 key='{key}' returned {len(raw)} unrecognised bytes "
                    f"(first 16 hex: {hex_preview}). "
                    f"Preview: {head_text[:200]!r}"
                )

            logger.error(detail)
            try:
                import tempfile, pathlib
                tmp = pathlib.Path(tempfile.gettempdir()) / f"debug_failed_image_fetch_{key.replace('/', '_')}.bin"
                tmp.write_bytes(raw)
                logger.error(f"Bad R2 payload saved to {tmp}")
            except Exception:
                pass
            raise R2DownloadError("not_image", detail)

        try:
            return Image.open(buf).convert("RGB")
        except Exception as exc:
            logger.error(f"PIL could not decode R2 key='{key}': {exc}")
            raise R2DownloadError("not_image", f"PIL decode failed for key='{key}': {exc}")

    def upload_image(self, image: Image.Image, folder: str = "cv-crops") -> str:
        key = f"{folder}/{uuid.uuid4()}.jpg"
        if not self.configured:
            return key
        buf = io.BytesIO()
        image.save(buf, format="JPEG", quality=90)
        buf.seek(0)
        self.client.put_object(
            Bucket=self.bucket,
            Key=key,
            Body=buf,
            ContentType="image/jpeg",
        )
        return key

    def upload_bytes(self, data: bytes, key: str, content_type: str = "image/jpeg") -> str:
        if not self.configured:
            return key
        self.client.put_object(
            Bucket=self.bucket,
            Key=key,
            Body=data,
            ContentType=content_type,
        )
        return key
