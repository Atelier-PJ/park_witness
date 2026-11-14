"""Private storage. Downloads always pass through incident authorization."""
import os
from pathlib import Path


class LocalStore:
    def __init__(self, root): self.root = Path(root)
    def put(self, key, data):
        path = self.root / key
        path.write_bytes(data)
        path.chmod(0o600)
    def get(self, key): return (self.root / key).read_bytes()
    def exists(self, key): return (self.root / key).is_file()
    def delete(self, key): (self.root / key).unlink(missing_ok=True)


class S3Store:
    def __init__(self):
        import boto3
        from botocore.config import Config
        self.bucket = os.environ['S3_BUCKET']
        self.client = boto3.client('s3', endpoint_url=os.getenv('S3_ENDPOINT_URL'),
            region_name=os.getenv('AWS_DEFAULT_REGION', 'us-east-1'),
            config=Config(signature_version='s3v4', s3={'addressing_style': 'path'},
                          connect_timeout=5, read_timeout=15, retries={'max_attempts': 2}))
    def put(self, key, data): self.client.put_object(Bucket=self.bucket, Key=key, Body=data)
    def get(self, key):
        from botocore.exceptions import ClientError
        try:
            body = self.client.get_object(Bucket=self.bucket, Key=key)['Body']
            try: return body.read()
            finally: body.close()
        except ClientError as e:
            if e.response['Error']['Code'] in ('NoSuchKey', '404'): raise FileNotFoundError(key) from e
            raise
    def exists(self, key):
        from botocore.exceptions import ClientError
        try: self.client.head_object(Bucket=self.bucket, Key=key); return True
        except ClientError as e:
            if e.response['Error']['Code'] in ('NoSuchKey', '404', 'NotFound'): return False
            raise
    def delete(self, key): self.client.delete_object(Bucket=self.bucket, Key=key)


def store(root):
    return S3Store() if os.getenv('STORAGE_BACKEND') == 's3' else LocalStore(root)
