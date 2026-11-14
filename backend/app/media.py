"""Reject corrupt media before it can enter a human review flow."""
import io
import subprocess
import tempfile
from pathlib import Path
from PIL import Image, UnidentifiedImageError
from fastapi import HTTPException


def validate_media(data):
    if data.startswith((b'\x89PNG\r\n\x1a\n', b'\xff\xd8\xff')):
        try:
            with Image.open(io.BytesIO(data)) as image:
                if image.format not in ('PNG', 'JPEG') or image.width * image.height > 25_000_000:
                    raise ValueError('image size')
                mime = Image.MIME[image.format]
                image.verify()
            with Image.open(io.BytesIO(data)) as image: image.load()
            return mime
        except (UnidentifiedImageError, OSError, SyntaxError, ValueError, Image.DecompressionBombError):
            raise HTTPException(415, '사진 파일이 손상됐거나 지원하지 않는 이미지입니다')
    if len(data) >= 12 and data[4:8] == b'ftyp':
        import imageio_ffmpeg
        with tempfile.TemporaryDirectory(prefix='witness-media-') as folder:
            path = Path(folder) / 'input.mp4'; path.write_bytes(data)
            try:
                result = subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-nostdin', '-v', 'error',
                    '-xerror', '-err_detect', 'explode', '-protocol_whitelist', 'file',
                    '-i', str(path), '-map', '0:v:0', '-t', '60', '-frames:v', '1800',
                    '-f', 'null', '-'], capture_output=True, timeout=25)
                if result.returncode == 0: return 'video/mp4'
            except (subprocess.TimeoutExpired, OSError): pass
        raise HTTPException(415, '영상을 읽을 수 없습니다. 짧은 MP4 공유본으로 다시 내보내주세요')
    raise HTTPException(415, 'PNG, JPEG 또는 MP4 공유본만 등록할 수 있습니다')
