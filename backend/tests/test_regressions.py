"""Regression scenarios from the application-spec audit. Disposable data only."""
import io
import os
import socket
import subprocess
import threading
from concurrent.futures import ThreadPoolExecutor
import pytest
from PIL import Image
from sqlalchemy import select, delete
from conftest import m, window, stamp
from test_workflow import PNG, upload, approve


def test_two_independent_files_can_be_approved(scenario):
    ctx, incident, request, _ = scenario
    c, db, h, v, u = ctx
    first, second = upload(ctx, request), upload(ctx, request)
    assert approve(ctx, request, first).status_code == 200
    assert approve(ctx, request, second).status_code == 200
    evidence = c.get('/api/incidents', headers=h['requester']).json()[0]['requests'][0]['evidence']
    assert {e['id'] for e in evidence} == {first, second}
    # Adding a further share copy must not disable access to already approved files.
    third = upload(ctx, request)
    assert c.post(f'/api/evidence/{first}/access', headers=h['requester']).status_code == 200
    assert approve(ctx, request, third).status_code == 200


def test_incident_retry_reuses_id_after_photo_failure(context):
    c, db, h, v, u = context
    body = window(v['requester'], description='photo retry', client_key='stable-client-key', discovered=stamp(-60))
    first = c.post('/api/incidents', headers=h['requester'], json=body).json()['id']
    assert c.post(f'/api/incidents/{first}/photo', headers=h['requester'], files={'file': ('bad.png', b'bad')}).status_code == 415
    second = c.post('/api/incidents', headers=h['requester'], json=body).json()['id']
    assert first == second
    assert len(c.get('/api/incidents', headers=h['requester']).json()) == 1
    photo = c.post(f'/api/incidents/{first}/photo', headers=h['requester'], files={'file': ('good.png', PNG)})
    assert photo.status_code == 201
    assert c.get(f'/api/photos/{photo.json()["id"]}', headers=h['requester']).content == PNG


def test_first_match_concurrency_creates_one_request(context):
    c, db, h, v, u = context
    c.post('/api/sessions', headers=h['witness'], json=window(v['witness'], consent=True))
    incident = c.post('/api/incidents', headers=h['requester'], json=window(v['requester'], description='first concurrent match')).json()['id']
    barrier = threading.Barrier(4)
    def match(_):
        barrier.wait()
        return c.post(f'/api/incidents/{incident}/match', headers=h['requester'])
    with ThreadPoolExecutor(max_workers=4) as pool: responses = list(pool.map(match, range(4)))
    assert [r.status_code for r in responses] == [200] * 4
    with db() as session: assert len(list(session.scalars(select(m.Request)))) == 1


def test_end_retry_preserves_confirmed_time(scenario):
    ctx, _, _, parking = scenario
    c, db, h, v, u = ctx
    with db() as session: original = session.get(m.Parking, parking).end
    assert c.post(f'/api/sessions/{parking}/end', headers=h['witness']).status_code == 200
    with db() as session: assert session.get(m.Parking, parking).end == original


def test_expired_approval_has_expired_display_and_no_access(scenario):
    ctx, _, request, _ = scenario
    c, db, h, v, u = ctx
    evidence = upload(ctx, request); assert approve(ctx, request, evidence).status_code == 200
    with db() as session: session.get(m.Evidence, evidence).expires = m.now()-1; session.commit()
    assert c.get('/api/inbox', headers=h['witness']).json()[0]['status'] == 'expired'
    assert c.post(f'/api/evidence/{evidence}/access', headers=h['requester']).status_code == 403


def test_missing_file_is_unavailable_not_server_error(scenario):
    ctx, _, request, _ = scenario
    c, db, h, v, u = ctx
    evidence = upload(ctx, request); assert approve(ctx, request, evidence).status_code == 200
    grant = c.post(f'/api/evidence/{evidence}/access', headers=h['requester']).json()['token']
    (m.FILES/evidence).unlink()
    assert c.get(f'/api/evidence/{evidence}/preview', headers=h['witness']).status_code == 410
    assert c.get(f'/api/evidence/{evidence}/download', params={'ticket': grant}, headers=h['requester']).status_code == 410
    assert c.get('/api/inbox', headers=h['witness']).json()[0]['evidence'][0]['available'] is False


def test_unconfigured_facility_does_not_guess_neighbors(context):
    c, db, h, v, u = context
    c.post('/api/sessions', headers=h['witness'], json=window(v['witness'], consent=True, zone='A'))
    incident = c.post('/api/incidents', headers=h['requester'], json=window(v['requester'], description='missing topology')).json()['id']
    assert c.post(f'/api/incidents/{incident}/match', headers=h['requester'], json={'include_adjacent': True}).status_code == 409
    assert c.get('/api/inbox', headers=h['witness']).json() == []


@pytest.mark.parametrize('content', [b'\x00\x00\x00\x0cftyp0000', b'\x89PNG\r\n\x1a\n', b'\xff\xd8\xffjunk'])
def test_corrupt_media_rejected(scenario, content):
    ctx, _, request, _ = scenario
    c, db, h, v, u = ctx
    res = c.post(f'/api/requests/{request}/evidence', headers=h['witness'], files={'file': ('broken', content)}, data={'kind': 'manual_share_copy'})
    assert res.status_code == 415
    assert c.get('/api/inbox', headers=h['witness']).json()[0]['evidence'] == []


def test_real_mp4_upload_preview_approval_download(scenario, tmp_path):
    import imageio_ffmpeg
    ctx, _, request, _ = scenario
    c, db, h, v, u = ctx
    path = tmp_path/'synthetic.mp4'
    subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-v', 'error', '-f', 'lavfi', '-i', 'color=c=green:s=64x64:r=5', '-t', '1', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', str(path)], check=True)
    data = path.read_bytes()
    response = c.post(f'/api/requests/{request}/evidence', headers=h['witness'], files={'file': ('share.mp4', data)}, data={'kind': 'manual_share_copy', 'processing_notes': '합성 재현 영상 1초; 개인정보 없음'})
    assert response.status_code == 201, response.text
    evidence = response.json()['id']
    assert c.get(f'/api/evidence/{evidence}/preview', headers=h['witness']).content == data
    assert approve(ctx, request, evidence).status_code == 200
    grant = c.post(f'/api/evidence/{evidence}/access', headers=h['requester']).json()['token']
    assert c.get(f'/api/evidence/{evidence}/download', headers=h['requester'], params={'ticket': grant}).content == data


def test_notifications_opt_in_smtp_and_opt_out(scenario, monkeypatch):
    from aiosmtpd.controller import Controller
    import app.worker as worker
    ctx, _, request, _ = scenario
    c, db, h, v, u = ctx
    monkeypatch.setattr(worker, 'DB', db)
    class Inbox:
        messages = []
        async def handle_DATA(self, server, session, envelope):
            self.messages.append(envelope); return '250 OK'
    sock = socket.socket(); sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]; sock.close()
    handler = Inbox(); controller = Controller(handler, hostname='127.0.0.1', port=port); controller.start()
    monkeypatch.setenv('SMTP_HOST', '127.0.0.1'); monkeypatch.setenv('SMTP_PORT', str(port))
    monkeypatch.setenv('SMTP_FROM', 'noreply@audit.local'); monkeypatch.setenv('SMTP_STARTTLS', 'false'); monkeypatch.setenv('PUBLIC_WEB_URL', 'http://127.0.0.1')
    try:
        assert c.get('/api/notifications/settings', headers=h['witness']).json()['email_enabled'] is False
        assert c.put('/api/notifications/settings', headers=h['witness'], json={'email_enabled': True}).status_code == 200
        assert c.post('/api/notifications/verification',headers=h['witness']).status_code==200
        assert worker.deliver_verifications()==1
        import re
        from email import policy
        from email.parser import BytesParser
        content=BytesParser(policy=policy.default).parsebytes(handler.messages[0].content).get_content()
        secret=re.search(r'email-verification=([^\s]+)',content).group(1)
        assert c.post('/api/notifications/verify',headers=h['stranger'],json={'token':secret}).status_code==410
        assert c.post('/api/notifications/verify',headers=h['witness'],json={'token':secret}).status_code==200
        handler.messages.clear()
        incident=c.post('/api/incidents',headers=h['requester'],json=window(v['requester'],description='이메일 확인 후 새 요청')).json()['id']
        assert c.post(f'/api/incidents/{incident}/match',headers=h['requester']).status_code==200
        with db() as session:assert len(list(session.scalars(select(m.Notification))))==1
        assert worker.deliver_notifications() == 1
        assert len(handler.messages) == 1
        assert handler.messages[0].rcpt_tos == ['witness@test.local']
        assert worker.deliver_notifications() == 0
        assert c.put('/api/notifications/settings', headers=h['witness'], json={'email_enabled': False}).status_code == 200
        with db() as session: row=session.scalar(select(m.Notification)); row.sent=None; session.commit()
        assert worker.deliver_notifications() == 0
        assert len(handler.messages) == 1
    finally: controller.stop()


@pytest.mark.skipif(not os.getenv('TEST_S3_ENDPOINT_URL'), reason='Disposable S3 service not configured')
def test_private_s3_roundtrip_and_retention(scenario, monkeypatch):
    import boto3
    import app.manage as manage
    ctx, incident, request, _ = scenario
    c, db, h, v, u = ctx
    monkeypatch.setenv('STORAGE_BACKEND', 's3')
    monkeypatch.setenv('S3_ENDPOINT_URL', os.environ['TEST_S3_ENDPOINT_URL'])
    monkeypatch.setenv('S3_BUCKET', 'witness-qa')
    monkeypatch.setenv('AWS_ACCESS_KEY_ID', 'witnessqa'); monkeypatch.setenv('AWS_SECRET_ACCESS_KEY', 'witness-qa-secret-only')
    client = boto3.client('s3', endpoint_url=os.environ['TEST_S3_ENDPOINT_URL'], region_name='us-east-1')
    if 'witness-qa' not in [x['Name'] for x in client.list_buckets()['Buckets']]: client.create_bucket(Bucket='witness-qa')
    evidence = upload(ctx, request, 'manual_share_copy'); assert approve(ctx, request, evidence).status_code == 200
    grant = c.post(f'/api/evidence/{evidence}/access', headers=h['requester']).json()['token']
    assert c.get(f'/api/evidence/{evidence}/download', params={'ticket': grant}, headers=h['requester']).content == PNG
    import httpx
    assert httpx.get(os.environ['TEST_S3_ENDPOINT_URL']+'/witness-qa/'+evidence).status_code == 403
    with db() as session: session.get(m.Incident, incident).closed=m.now()-31*86400; session.commit()
    monkeypatch.setattr(manage, 'DB', db); monkeypatch.setattr(manage, 'FILES', m.FILES)
    manage.cleanup()
    with pytest.raises(client.exceptions.NoSuchKey): client.get_object(Bucket='witness-qa', Key=evidence)


def test_email_unavailable_is_not_reported_as_enabled(context, monkeypatch):
    c, db, h, v, u = context
    monkeypatch.delenv('SMTP_HOST',raising=False)
    assert c.get('/api/notifications/settings',headers=h['witness']).json()['available'] is False
    assert c.put('/api/notifications/settings',headers=h['witness'],json={'email_enabled':True}).status_code==503


def test_changed_idempotent_submission_is_rejected(context):
    c, db, h, v, u=context
    body=window(v['requester'],description='initial incident',client_key='same-key-must-not-change')
    assert c.post('/api/incidents',headers=h['requester'],json=body).status_code==201
    body['description']='different incident'
    assert c.post('/api/incidents',headers=h['requester'],json=body).status_code==409
    assert len(c.get('/api/incidents',headers=h['requester']).json())==1


def test_expired_email_confirmation_denied(context):
    c, db, h, v, u=context
    with db() as session:
        session.add(m.NotificationPreference(owner=u['witness'],email_enabled=True))
        session.add(m.EmailVerification(token_hash=m.digest('a'*40),delivery_token=None,owner=u['witness'],expires=m.now()-1));session.commit()
    assert c.post('/api/notifications/verify',headers=h['witness'],json={'token':'a'*40}).status_code==410
