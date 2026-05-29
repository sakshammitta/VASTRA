import io
import os
import uuid
import boto3
from botocore.config import Config
from PIL import Image


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
        else:
            self.client = None
            self.configured = False

    def download_image(self, key: str) -> Image.Image:
        if not self.configured:
            raise RuntimeError(f"R2 not configured, cannot fetch key: {key}")
        buf = io.BytesIO()
        self.client.download_fileobj(self.bucket, key, buf)
        buf.seek(0)
        return Image.open(buf).convert("RGB")

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
