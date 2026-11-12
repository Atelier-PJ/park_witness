import importlib
import os
from datetime import datetime, timezone
from pathlib import Path
import tempfile

os.environ['DATA_DIR']=tempfile.mkdtemp(prefix='witness-test-bootstrap-')
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
m=importlib.import_module('app.main')

@pytest.fixture
def context(tmp_path,monkeypatch):
    test_url=os.environ.get('TEST_DATABASE_URL')
    if test_url:
        assert test_url.endswith('/witness_test'), 'Only a disposable witness_test database is permitted'
        engine=create_engine(test_url)
        m.Base.metadata.drop_all(engine)
    else:
        engine=create_engine('sqlite:///'+str(tmp_path/'test.db'),connect_args={'check_same_thread':False})
    m.Base.metadata.create_all(engine);dbmaker=sessionmaker(engine,expire_on_commit=False)
    monkeypatch.setattr(m,'DB',dbmaker);monkeypatch.setattr(m,'FILES',tmp_path/'files');m.FILES.mkdir()
    users={}; vehicles={}; headers={}
    with dbmaker() as db:
        for name in ['requester','witness','stranger','unverified']:
            u=m.User(email=name+'@test.local',password=m.password_hash('test-password-123'));db.add(u);db.flush()
            v=m.Vehicle(owner=u.id,label=name,verified=name!='unverified',source='test_fixture');db.add(v);db.flush();users[name]=u.id;vehicles[name]=v.id
        db.commit()
    with TestClient(m.app) as client:
        for name in users:
            res=client.post('/api/login',json={'email':name+'@test.local','password':'test-password-123'})
            headers[name]={'Authorization':'Bearer '+res.json()['token']}
        yield client,dbmaker,headers,vehicles,users
    engine.dispose()

def stamp(delta):return datetime.fromtimestamp(m.now()+delta,timezone.utc).isoformat()
def window(v,**kwargs):
    return {'vehicle':v,'facility':'PARK','floor':'B2','zone':'B','start':stamp(-3600),'end':stamp(-1800),**kwargs}

@pytest.fixture
def scenario(context):
    c,db,h,v,u=context
    s=c.post('/api/sessions',headers=h['witness'],json=window(v['witness'],consent=True,direction='north'));assert s.status_code==201,s.text
    i=c.post('/api/incidents',headers=h['requester'],json=window(v['requester'],description='손상 구간 확인 요청'));assert i.status_code==201,i.text
    id=i.json()['id'];res=c.post(f'/api/incidents/{id}/match',headers=h['requester']);assert res.status_code==200,res.text
    return context,id,res.json()['candidates'][0]['id'],s.json()['id']
