"""Isolated browser-test fixture; never attaches to an existing database."""
import io
import os
import socket
import tempfile
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
os.environ.pop('DATABASE_URL', None)
os.environ.pop('STORAGE_BACKEND', None)
os.environ.pop('SMTP_HOST', None)
os.environ['DATA_DIR'] = tempfile.mkdtemp(prefix='witness-browser-test-')
from app import main as m
from PIL import Image
import uvicorn
with m.DB() as db:
    for name in ('requester', 'witness', 'other'):
        user=m.User(email=name+'@e2e.local', password=m.password_hash('witness-e2e-password'));db.add(user);db.flush()
        vehicle=m.Vehicle(owner=user.id,label='시험 차량 '+name,verified=True,source='test_fixture');db.add(vehicle);db.flush()
        if name=='witness':db.add(m.Parking(owner=user.id,vehicle=vehicle.id,facility='E2E-PARK',floor='B2',zone='B',direction='north',start=m.now()-7200,end=m.now()-60,observed=m.now()-60,expires=m.now()+86400,consent=True,source='mock'))
    db.commit()
sock=socket.socket();sock.bind(('127.0.0.1',0))
print('E2E_PORT='+str(sock.getsockname()[1]),flush=True)
uvicorn.Server(uvicorn.Config(m.app,log_level='warning')).run(sockets=[sock])
