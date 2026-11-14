import hashlib
import io
import json
from concurrent.futures import ThreadPoolExecutor
import pytest
from sqlalchemy import select
from conftest import m,window,stamp

# Valid 1x1 PNG, representing an explicitly synthetic share copy.
import base64
from PIL import Image
_png=io.BytesIO();Image.new('RGB',(16,16),'green').save(_png,format='PNG');PNG=_png.getvalue()

def upload(context,r,kind='mock'):
    c,db,h,v,u=context
    res=c.post(f'/api/requests/{r}/evidence',headers=h['witness'],files={'file':('share.png',PNG,'image/png')},data={'kind':kind})
    assert res.status_code==201,res.text
    return res.json()['id']
def approve(context,r,e):
    c,db,h,v,u=context
    return c.post(f'/api/requests/{r}/approve',headers=h['witness'],json={'evidence':e,'reviewed_share_copy':True,'recipient_and_purpose_confirmed':True,'hours':1})

def test_complete_approval_download_and_audit(scenario):
    context,i,r,s=scenario;c,db,h,v,u=context
    e=upload(context,r)
    assert c.post(f'/api/evidence/{e}/access',headers=h['requester']).status_code==403
    assert c.get('/api/incidents',headers=h['requester']).json()[0]['requests'][0]['evidence']==[]
    assert c.get(f'/api/evidence/{e}/preview',headers=h['requester']).status_code==404
    assert c.get(f'/api/evidence/{e}/preview',headers=h['witness']).content==PNG
    assert approve(context,r,e).status_code==200
    grant=c.post(f'/api/evidence/{e}/access',headers=h['requester']).json()
    assert grant['expires_in']<=300
    result=c.get(f'/api/evidence/{e}/download',params={'ticket':grant['token']},headers=h['requester'])
    assert result.content==PNG
    detail=c.get('/api/incidents',headers=h['requester']).json()[0]['requests'][0]['evidence'][0]
    assert detail['sha256']==hashlib.sha256(PNG).hexdigest() and detail['kind']=='mock'
    actions=[a['action'] for a in c.get('/api/audit',headers=h['requester']).json()]
    assert 'download' in actions and 'access_issued' in actions

@pytest.mark.parametrize('change',[{'floor':'B3'},{'facility':'OTHER'},{'start':stamp(-7000),'end':stamp(-5000)}])
def test_excludes_wrong_facility_floor_or_time(context,change):
    c,db,h,v,u=context
    assert c.post('/api/sessions',headers=h['witness'],json=window(v['witness'],consent=True,**change)).status_code==201
    i=c.post('/api/incidents',headers=h['requester'],json=window(v['requester'],description='피해 확인')).json()['id']
    assert c.post(f'/api/incidents/{i}/match',headers=h['requester']).json()['candidates']==[]

def test_candidate_projection_anonymous_and_idempotent(scenario):
    context,i,r,s=scenario;c,db,h,v,u=context
    for _ in range(3):
        res=c.post(f'/api/incidents/{i}/match',headers=h['requester']).json()
        assert len(res['candidates'])==1
        text=json.dumps(res)
        for private in [u['witness'],v['witness'],s,'@test.local','north']:
            assert private not in text
    assert len(c.get('/api/inbox',headers=h['witness']).json())==1

def test_concurrent_match_does_not_duplicate(scenario):
    context,i,r,s=scenario;c,db,h,v,u=context
    with ThreadPoolExecutor(max_workers=4) as pool:
        responses=list(pool.map(lambda _:c.post(f'/api/incidents/{i}/match',headers=h['requester']),range(8)))
    assert all(res.status_code==200 for res in responses)
    with db() as session:assert len(list(session.scalars(select(m.Request).where(m.Request.incident==i))))==1

@pytest.mark.parametrize('who',['stranger','witness'])
def test_only_incident_owner_can_match_or_close(scenario,who):
    context,i,r,s=scenario;c,db,h,v,u=context
    assert c.post(f'/api/incidents/{i}/match',headers=h[who]).status_code==404
    assert c.post(f'/api/incidents/{i}/close',headers=h[who]).status_code==404
    assert c.get(f'/api/incidents/{i}/photos',headers=h[who]).status_code==404

@pytest.mark.parametrize('who',['stranger','requester'])
def test_only_provider_can_respond_and_upload(scenario,who):
    context,i,r,s=scenario;c,db,h,v,u=context
    assert c.post(f'/api/requests/{r}/respond',headers=h[who],json={'status':'review'}).status_code==404
    assert c.post(f'/api/requests/{r}/evidence',headers=h[who],files={'file':('x.png',PNG)},data={'kind':'mock'}).status_code==404

@pytest.mark.parametrize('status',['no_video','rejected'])
def test_negative_response_cannot_be_overwritten(scenario,status):
    context,i,r,s=scenario;c,db,h,v,u=context
    assert c.post(f'/api/requests/{r}/respond',headers=h['witness'],json={'status':status}).status_code==200
    assert c.post(f'/api/requests/{r}/respond',headers=h['witness'],json={'status':'review'}).status_code==409
    assert c.post(f'/api/requests/{r}/evidence',headers=h['witness'],files={'file':('x.png',PNG)},data={'kind':'mock'}).status_code==409

def test_unverified_or_someone_elses_vehicle_denied(context):
    c,db,h,v,u=context
    for actor,vehicle,code in [('unverified',v['unverified'],403),('requester',v['witness'],404)]:
        assert c.post('/api/incidents',headers=h[actor],json=window(vehicle,description='피해 확인')).status_code==code
        assert c.post('/api/sessions',headers=h[actor],json=window(vehicle,consent=True)).status_code==code
    new=c.post('/api/vehicles',headers=h['requester'],json={'label':'another'}).json()
    assert new['verified'] is False

def test_unauthenticated_login_logout_and_password(context):
    c,db,h,v,u=context
    assert c.get('/api/inbox').status_code in [401,403]
    assert c.post('/api/login',json={'email':'requester@test.local','password':'wrong-password'}).status_code==401
    assert c.post('/api/logout',headers=h['requester']).status_code==200
    assert c.get('/api/me',headers=h['requester']).status_code==401

def test_expired_request_read_only_and_pending_no_response(scenario):
    context,i,r,s=scenario;c,db,h,v,u=context
    assert c.get('/api/inbox',headers=h['witness']).json()[0]['status']=='pending'
    with db() as session:session.get(m.Request,r).expires=m.now()-1;session.commit()
    assert c.get('/api/inbox',headers=h['witness']).json()[0]['status']=='expired'
    assert c.post(f'/api/requests/{r}/respond',headers=h['witness'],json={'status':'checking'}).status_code==410

@pytest.mark.parametrize('reason',['ticket_expired','evidence_expired','revoked','closed','ticket_for_other_user'])
def test_old_link_denied_after_state_changes(scenario,reason):
    context,i,r,s=scenario;c,db,h,v,u=context
    e=upload(context,r);assert approve(context,r,e).status_code==200
    ticket=c.post(f'/api/evidence/{e}/access',headers=h['requester']).json()['token']
    actor='requester'
    if reason=='revoked':assert c.post(f'/api/requests/{r}/revoke',headers=h['witness']).status_code==200
    elif reason=='closed':assert c.post(f'/api/incidents/{i}/close',headers=h['requester']).status_code==200
    elif reason=='ticket_for_other_user':actor='stranger'
    else:
        with db() as session:
            if reason=='ticket_expired': session.get(m.Link,m.digest(ticket)).expires=m.now()-1
            else:session.get(m.Evidence,e).expires=m.now()-1
            session.commit()
    assert c.get(f'/api/evidence/{e}/download',headers=h[actor],params={'ticket':ticket}).status_code in [403,404]
    assert c.get(f'/api/evidence/{e}/download',params={'ticket':ticket}).status_code in [401,403]

def test_approval_requires_explicit_review_and_recipient_confirmation(scenario):
    context,i,r,s=scenario;c,db,h,v,u=context
    e=upload(context,r)
    assert c.post(f'/api/requests/{r}/approve',headers=h['witness'],json={'evidence':e,'reviewed_share_copy':False,'recipient_and_purpose_confirmed':True,'hours':1}).status_code==409
    assert c.post(f'/api/requests/{r}/approve',headers=h['witness'],json={'evidence':e,'reviewed_share_copy':True,'recipient_and_purpose_confirmed':True,'hours':25}).status_code==422
    assert c.post(f'/api/evidence/{e}/access',headers=h['stranger']).status_code==404

def test_untrusted_upload_types_and_private_files(scenario):
    context,i,r,s=scenario;c,db,h,v,u=context
    assert c.post(f'/api/requests/{r}/evidence',headers=h['witness'],files={'file':('bad.png',b'<script>bad()</script>','image/png')},data={'kind':'mock'}).status_code==415
    assert c.post(f'/api/requests/{r}/evidence',headers=h['witness'],files={'file':('../../file.png',PNG)},data={'kind':'sdk_camera'}).status_code==422
    e=upload(context,r)
    assert c.get('/.data/private/'+e).status_code==404
    assert c.get('/api/photos/'+e,headers=h['stranger']).status_code==404

def test_damage_photo_is_private(scenario):
    context,i,r,s=scenario;c,db,h,v,u=context
    photo=c.post(f'/api/incidents/{i}/photo',headers=h['requester'],files={'file':('damage.png',PNG)}).json()['id']
    assert c.get(f'/api/photos/{photo}',headers=h['requester']).content==PNG
    assert c.get(f'/api/photos/{photo}',headers=h['witness']).status_code==404
    assert photo not in json.dumps(c.get('/api/inbox',headers=h['witness']).json())

def test_participation_withdrawal_and_expiry(context):
    c,db,h,v,u=context
    s=c.post('/api/sessions',headers=h['witness'],json=window(v['witness'],consent=True)).json()['id']
    assert c.post(f'/api/sessions/{s}/withdraw',headers=h['stranger']).status_code==404
    c.post(f'/api/sessions/{s}/withdraw',headers=h['witness'])
    i=c.post('/api/incidents',headers=h['requester'],json=window(v['requester'],description='피해 확인')).json()['id']
    assert c.post(f'/api/incidents/{i}/match',headers=h['requester']).json()['candidates']==[]
    with db() as session:p=session.get(m.Parking,s);p.consent=True;p.expires=m.now()-1;session.commit()
    assert c.post(f'/api/incidents/{i}/match',headers=h['requester']).json()['candidates']==[]
    assert c.get('/api/sessions',headers=h['witness']).json()==[]

def test_unknown_end_never_extrapolates_past_observed(context):
    c,db,h,v,u=context
    s=c.post('/api/sessions',headers=h['witness'],json=window(v['witness'],consent=True,end=None)).json()['id']
    with db() as session:session.get(m.Parking,s).observed=m.now()-2400;session.commit()
    i=c.post('/api/incidents',headers=h['requester'],json=window(v['requester'],description='피해 확인',start=stamp(-1800),end=stamp(-600))).json()['id']
    assert c.post(f'/api/incidents/{i}/match',headers=h['requester']).json()['candidates']==[]

def test_block_reporting_and_quota(scenario):
    context,i,r,s=scenario;c,db,h,v,u=context
    assert c.post(f'/api/requests/{r}/report',headers=h['witness']).json()['status']=='recorded_for_operator_review'
    assert c.post(f'/api/requests/{r}/block',headers=h['witness']).status_code==200
    for _ in range(4):
        n=c.post('/api/incidents',headers=h['requester'],json=window(v['requester'],description='피해 확인')).json()['id']
        assert c.post(f'/api/incidents/{n}/match',headers=h['requester']).json()['candidates']==[]
    assert c.post('/api/incidents',headers=h['requester'],json=window(v['requester'],description='피해 확인')).status_code==429

@pytest.mark.parametrize('change',[{'start':'2026-11-12T10:00:00'},{'start':stamp(3600),'end':stamp(7200)},{'start':stamp(-1800),'end':stamp(-3600)},{'start':stamp(-90000),'end':stamp(-100)}])
def test_reject_invalid_time_windows(context,change):
    c,db,h,v,u=context
    assert c.post('/api/incidents',headers=h['requester'],json=window(v['requester'],description='피해 확인',**change)).status_code==422

def test_retention_deletes_files_and_sessions(scenario):
    context,i,r,s=scenario;c,db,h,v,u=context
    e=upload(context,r)
    photo=c.post(f'/api/incidents/{i}/photo',headers=h['requester'],files={'file':('damage.png',PNG)}).json()['id']
    with db() as session:
        session.get(m.Incident,i).closed=m.now()-31*86400
        session.get(m.Parking,s).expires=m.now()-1
        session.commit()
    import app.manage as manage
    # manage imports values at module import; use this test's database and private directory.
    manage.DB=db;manage.FILES=m.FILES;manage.cleanup()
    with db() as session:
        assert session.get(m.Incident,i) is None
        assert session.get(m.Parking,s) is None
        assert session.get(m.Evidence,e) is None
    assert not (m.FILES/e).exists() and not (m.FILES/photo).exists()

def test_restart_preserves_data_and_web_contract(scenario):
    context,i,r,s=scenario;c,db,h,v,u=context
    from fastapi.testclient import TestClient
    with TestClient(m.app) as restarted:
        assert restarted.get('/api/incidents',headers=h['requester']).json()[0]['id']==i
        html=restarted.get('/')
        assert html.status_code==200 and 'Park Witness' in html.text
        assert html.headers['cache-control']=='no-store'
        assert "frame-ancestors 'none'" in html.headers['content-security-policy']

def test_adjacent_zone_requires_explicit_scope_expansion(context):
    c,db,h,v,u=context
    with db() as session:
        session.add(m.FacilityLayout(facility='PARK',adjacency_json='{"A":["B"],"B":["A","C"],"C":["B"]}'));session.commit()
    c.post('/api/sessions',headers=h['witness'],json=window(v['witness'],consent=True,zone='A'))
    i=c.post('/api/incidents',headers=h['requester'],json=window(v['requester'],description='피해 확인')).json()['id']
    assert c.post(f'/api/incidents/{i}/match',headers=h['requester']).json()['candidates']==[]
    assert len(c.post(f'/api/incidents/{i}/match',headers=h['requester'],json={'include_adjacent':True}).json()['candidates'])==1
