"""Consent-based witness MVP. No camera, V2V, video extraction or identity-provider claims."""
from __future__ import annotations
import hashlib
import hmac
import os
import secrets
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal

from fastapi import Depends, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field, model_validator
from sqlalchemy import Boolean, Float, ForeignKey, String, UniqueConstraint, create_engine, select, delete
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, sessionmaker

ROOT = Path(__file__).resolve().parents[2]
DATA = Path(os.getenv('DATA_DIR', str(ROOT / '.data')))
DATA.mkdir(parents=True, exist_ok=True)
FILES = DATA / 'private'; FILES.mkdir(exist_ok=True)
engine = create_engine(os.getenv('DATABASE_URL', f'sqlite:///{DATA}/witness.db'), **({'connect_args': {'check_same_thread': False}} if os.getenv('DATABASE_URL', 'sqlite:').startswith('sqlite:') else {}))
DB = sessionmaker(engine, expire_on_commit=False)

def now(): return datetime.now(timezone.utc).timestamp()
def uid(): return uuid.uuid4().hex
def digest(value: str): return hashlib.sha256(value.encode()).hexdigest()
def fail(code, message): raise HTTPException(code, message)

class Base(DeclarativeBase): pass
class User(Base):
    __tablename__ = 'users'
    id: Mapped[str] = mapped_column(String, primary_key=True, default=uid)
    email: Mapped[str] = mapped_column(String, unique=True)
    password: Mapped[str] = mapped_column(String)
class Login(Base):
    __tablename__ = 'logins'
    token: Mapped[str] = mapped_column(String, primary_key=True)
    owner: Mapped[str] = mapped_column(ForeignKey('users.id'))
    expires: Mapped[float] = mapped_column(Float)
class Vehicle(Base):
    __tablename__ = 'vehicles'
    id: Mapped[str] = mapped_column(String, primary_key=True, default=uid)
    owner: Mapped[str] = mapped_column(ForeignKey('users.id'))
    label: Mapped[str] = mapped_column(String)
    verified: Mapped[bool] = mapped_column(Boolean, default=False)
    source: Mapped[str] = mapped_column(String, default='pending_operator_review')
class Parking(Base):
    __tablename__ = 'parking'
    id: Mapped[str] = mapped_column(String, primary_key=True, default=uid)
    owner: Mapped[str] = mapped_column(ForeignKey('users.id'))
    vehicle: Mapped[str] = mapped_column(ForeignKey('vehicles.id'))
    facility: Mapped[str] = mapped_column(String)
    floor: Mapped[str] = mapped_column(String)
    zone: Mapped[str] = mapped_column(String)
    direction: Mapped[str] = mapped_column(String)
    start: Mapped[float] = mapped_column(Float)
    end: Mapped[float | None] = mapped_column(Float, nullable=True)
    observed: Mapped[float] = mapped_column(Float)
    expires: Mapped[float] = mapped_column(Float)
    consent: Mapped[bool] = mapped_column(Boolean, default=True)
    source: Mapped[str] = mapped_column(String)
class Incident(Base):
    __tablename__ = 'incidents'
    id: Mapped[str] = mapped_column(String, primary_key=True, default=uid)
    owner: Mapped[str] = mapped_column(ForeignKey('users.id'))
    vehicle: Mapped[str] = mapped_column(ForeignKey('vehicles.id'))
    facility: Mapped[str] = mapped_column(String)
    floor: Mapped[str] = mapped_column(String)
    zone: Mapped[str] = mapped_column(String)
    start: Mapped[float] = mapped_column(Float)
    end: Mapped[float] = mapped_column(Float)
    description: Mapped[str] = mapped_column(String)
    closed: Mapped[float | None] = mapped_column(Float, nullable=True)
    created: Mapped[float] = mapped_column(Float, default=now)
class Request(Base):
    __tablename__ = 'requests'
    __table_args__ = (UniqueConstraint('incident', 'provider'),)
    id: Mapped[str] = mapped_column(String, primary_key=True, default=uid)
    incident: Mapped[str] = mapped_column(ForeignKey('incidents.id'))
    provider: Mapped[str] = mapped_column(ForeignKey('users.id'))
    alias: Mapped[str] = mapped_column(String, default=lambda: '목격 후보 '+secrets.token_hex(3))
    status: Mapped[str] = mapped_column(String, default='pending')
    expires: Mapped[float] = mapped_column(Float, default=lambda: now()+48*3600)
    created: Mapped[float] = mapped_column(Float, default=now)
class Evidence(Base):
    __tablename__ = 'evidence'
    id: Mapped[str] = mapped_column(String, primary_key=True, default=uid)
    request: Mapped[str] = mapped_column(ForeignKey('requests.id'))
    hash: Mapped[str] = mapped_column(String)
    mime: Mapped[str] = mapped_column(String)
    kind: Mapped[str] = mapped_column(String)
    approved: Mapped[bool] = mapped_column(Boolean, default=False)
    expires: Mapped[float] = mapped_column(Float, default=0)
    created: Mapped[float] = mapped_column(Float, default=now)
class Photo(Base):
    __tablename__ = 'photos'
    id: Mapped[str] = mapped_column(String, primary_key=True, default=uid)
    incident: Mapped[str] = mapped_column(ForeignKey('incidents.id'))
    mime: Mapped[str] = mapped_column(String)
    hash: Mapped[str] = mapped_column(String)
class Link(Base):
    __tablename__ = 'links'
    token: Mapped[str] = mapped_column(String, primary_key=True)
    evidence: Mapped[str] = mapped_column(ForeignKey('evidence.id'))
    owner: Mapped[str] = mapped_column(ForeignKey('users.id'))
    expires: Mapped[float] = mapped_column(Float)
class Audit(Base):
    __tablename__ = 'audit'
    id: Mapped[str] = mapped_column(String, primary_key=True, default=uid)
    actor: Mapped[str] = mapped_column(String)
    action: Mapped[str] = mapped_column(String)
    subject: Mapped[str] = mapped_column(String)
    at: Mapped[float] = mapped_column(Float, default=now)
class Block(Base):
    __tablename__ = 'blocks'
    __table_args__ = (UniqueConstraint('owner', 'target'),)
    id: Mapped[str] = mapped_column(String, primary_key=True, default=uid)
    owner: Mapped[str] = mapped_column(String)
    target: Mapped[str] = mapped_column(String)
Base.metadata.create_all(engine)

def audit(db, user, action, subject): db.add(Audit(actor=user.id, action=action, subject=subject))
def getdb():
    with DB() as db:
        yield db
bearer = HTTPBearer()
def current(auth: HTTPAuthorizationCredentials = Depends(bearer), db: Session = Depends(getdb)):
    login = db.get(Login, digest(auth.credentials))
    if not login or login.expires <= now(): fail(401, '로그인이 필요합니다')
    user = db.get(User, login.owner)
    if not user: fail(401, '계정을 찾을 수 없습니다')
    return user

def owned(db, model, id, user):
    item = db.get(model, id)
    if not item or item.owner != user.id: fail(404, '항목을 찾을 수 없습니다')
    return item

def vehicle_owned(db, id, user):
    vehicle = owned(db, Vehicle, id, user)
    if not vehicle.verified: fail(403, '차량 사용 권한의 운영자 확인이 필요합니다')
    return vehicle

def password_hash(password):
    salt=secrets.token_hex(16)
    return salt+':'+hashlib.scrypt(password.encode(), salt=salt.encode(), n=16384, r=8, p=1).hex()
def password_ok(password, stored):
    salt, expected=stored.split(':')
    return hmac.compare_digest(expected, hashlib.scrypt(password.encode(), salt=salt.encode(), n=16384, r=8, p=1).hex())

class Credentials(BaseModel):
    email: str = Field(min_length=3, max_length=120, pattern=r'^[^\s@]+@[^\s@]+\.[^\s@]+$')
    password: str = Field(min_length=10, max_length=128)
class VehicleInput(BaseModel):
    label: str = Field(min_length=1, max_length=40)
class Window(BaseModel):
    facility: str = Field(min_length=1, max_length=80, pattern=r'^\S(?:.*\S)?$')
    floor: str = Field(min_length=1, max_length=12, pattern=r'^\S+$')
    zone: str = Field(min_length=1, max_length=20, pattern=r'^\S+$')
    start: datetime
    end: datetime | None = None
    @model_validator(mode='after')
    def times(self):
        for value in [self.start, self.end]:
            if value and (value.tzinfo is None or value.timestamp() > now()+60): raise ValueError('시간대가 있는 과거/현재 시각을 입력하세요')
        if self.end and self.end <= self.start: raise ValueError('종료는 시작 이후여야 합니다')
        if self.start.timestamp() < now()-72*3600: raise ValueError('최근 72시간 이내만 등록할 수 있습니다')
        return self
class ParkingInput(Window):
    vehicle: str
    direction: Literal['north','east','south','west','unknown'] = 'unknown'
    consent: bool
class IncidentInput(Window):
    vehicle: str
    description: str = Field(min_length=3, max_length=1000)
    @model_validator(mode='after')
    def interval(self):
        if not self.end or (self.end-self.start).total_seconds()>24*3600: raise ValueError('사건 시간 범위는 24시간 이내여야 합니다')
        return self
class ResponseInput(BaseModel):
    status: Literal['checking','review','no_video','rejected']
class MatchInput(BaseModel):
    include_adjacent: bool = False
class Approval(BaseModel):
    evidence: str
    reviewed_share_copy: bool
    recipient_and_purpose_confirmed: bool
    hours: int = Field(default=24, ge=1, le=24)

app=FastAPI(title='Park Witness', version='0.1.0')
@app.middleware('http')
async def security_headers(request, call_next):
    response = await call_next(request)
    response.headers['Cache-Control']='no-store'
    response.headers['X-Content-Type-Options']='nosniff'
    response.headers['Referrer-Policy']='no-referrer'
    response.headers['Content-Security-Policy']="default-src 'self'; script-src 'self'; style-src 'self'; media-src 'self' blob:; img-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    return response
@app.get('/api/health')
def health(): return {'status':'ok','vehicle_sdk':'unverified','video_source':'manual_upload_or_mock'}
@app.post('/api/register', status_code=201)
def register(body: Credentials, db: Session=Depends(getdb)):
    if db.scalar(select(User).where(User.email==body.email.lower())): fail(409,'이미 등록된 계정입니다')
    user=User(email=body.email.lower(),password=password_hash(body.password));db.add(user);db.commit()
    return {'id':user.id,'vehicle_verification':'operator_review_required'}
@app.post('/api/login')
def login(body: Credentials, db: Session=Depends(getdb)):
    user=db.scalar(select(User).where(User.email==body.email.lower()))
    if not user or not password_ok(body.password,user.password): fail(401,'로그인 정보가 올바르지 않습니다')
    token=secrets.token_urlsafe(32); db.add(Login(token=digest(token),owner=user.id,expires=now()+8*3600));db.commit()
    return {'token':token,'expires_in':28800}
@app.post('/api/logout')
def logout(auth: HTTPAuthorizationCredentials=Depends(bearer),user=Depends(current),db:Session=Depends(getdb)):
    db.execute(delete(Login).where(Login.token==digest(auth.credentials)));db.commit();return {'ok':True}
@app.get('/api/me')
def me(user=Depends(current),db:Session=Depends(getdb)):
    return {'email':user.email,'vehicles':[{'id':v.id,'label':v.label,'verified':v.verified,'source':v.source} for v in db.scalars(select(Vehicle).where(Vehicle.owner==user.id))]}
@app.post('/api/vehicles',status_code=201)
def add_vehicle(body:VehicleInput,user=Depends(current),db:Session=Depends(getdb)):
    v=Vehicle(owner=user.id,label=body.label);db.add(v);db.commit();return {'id':v.id,'verified':False}
@app.post('/api/sessions',status_code=201)
def parking(body:ParkingInput,user=Depends(current),db:Session=Depends(getdb)):
    vehicle_owned(db,body.vehicle,user)
    if not body.consent: fail(422,'네트워크 참여 동의가 필요합니다')
    t=now();p=Parking(owner=user.id,vehicle=body.vehicle,facility=body.facility,floor=body.floor.upper(),zone=body.zone.upper(),direction=body.direction,start=body.start.timestamp(),end=body.end.timestamp() if body.end else None,observed=t,expires=body.start.timestamp()+72*3600,consent=True,source='manual')
    db.add(p);db.flush();audit(db,user,'participation_manual',p.id);db.commit();return {'id':p.id,'source':p.source,'end_confirmed':p.end is not None}
@app.get('/api/sessions')
def sessions(user=Depends(current),db:Session=Depends(getdb)):
    return [{'id':p.id,'facility':p.facility,'floor':p.floor,'zone':p.zone,'start':p.start,'end':p.end,'last_observed':p.observed,'expires':p.expires,'consent':p.consent,'source':p.source} for p in db.scalars(select(Parking).where(Parking.owner==user.id,Parking.expires>now()))]
@app.post('/api/sessions/{id}/withdraw')
def withdraw(id:str,user=Depends(current),db:Session=Depends(getdb)):
    p=owned(db,Parking,id,user);p.consent=False;audit(db,user,'participation_withdrawn',id);db.commit();return {'ok':True}
@app.post('/api/sessions/{id}/end')
def end_session(id:str,user=Depends(current),db:Session=Depends(getdb)):
    p=owned(db,Parking,id,user)
    if p.expires<=now(): fail(410,'만료된 세션입니다')
    p.end=now();p.observed=now();audit(db,user,'end_confirmed_manual',id);db.commit();return {'ok':True}
@app.post('/api/incidents',status_code=201)
def incident(body:IncidentInput,user=Depends(current),db:Session=Depends(getdb)):
    vehicle_owned(db,body.vehicle,user)
    if len(list(db.scalars(select(Incident).where(Incident.owner==user.id,Incident.created>now()-86400))))>=5: fail(429,'하루 사건 등록 한도에 도달했습니다')
    item=Incident(owner=user.id,vehicle=body.vehicle,facility=body.facility,floor=body.floor.upper(),zone=body.zone.upper(),start=body.start.timestamp(),end=body.end.timestamp(),description=body.description)
    db.add(item);db.flush();audit(db,user,'incident_created',item.id);db.commit();return {'id':item.id}

def request_view(db,r,user):
    i=db.get(Incident,r.incident)
    # No owner id, plate, contact, parking session or full location history in this projection.
    status='expired' if r.status not in ['approved','rejected','no_video','revoked'] and r.expires<=now() else r.status
    result={'id':r.id,'alias':r.alias,'status':status,'expires':r.expires,'incident':i.id,'facility':i.facility,'floor':i.floor,'zone':i.zone,'start':i.start,'end':i.end,'purpose':i.description,'closed':i.closed is not None}
    result['evidence']=[{'id':e.id,'sha256':e.hash,'kind':e.kind,'approved':e.approved,'expires':e.expires,'mime':e.mime} for e in db.scalars(select(Evidence).where(Evidence.request==r.id)) if r.provider==user.id or (e.approved and e.expires>now() and r.status=='approved' and not i.closed)]
    return result
@app.get('/api/incidents')
def incidents(user=Depends(current),db:Session=Depends(getdb)):
    return [{'id':i.id,'facility':i.facility,'floor':i.floor,'zone':i.zone,'description':i.description,'closed':bool(i.closed),'requests':[request_view(db,r,user) for r in db.scalars(select(Request).where(Request.incident==i.id))]} for i in db.scalars(select(Incident).where(Incident.owner==user.id))]
@app.post('/api/incidents/{id}/match')
def match(id:str,body:MatchInput|None=None,user=Depends(current),db:Session=Depends(getdb)):
    i=owned(db,Incident,id,user)
    if i.closed: fail(409,'종료된 사건입니다')
    candidates=list(db.scalars(select(Parking).where(Parking.facility==i.facility,Parking.floor==i.floor,Parking.consent==True,Parking.expires>now(),Parking.owner!=user.id,Parking.start<i.end)))
    candidates=[p for p in candidates if min(p.end if p.end is not None else p.observed,p.expires)>i.start and db.get(Vehicle,p.vehicle).verified]
    adjacent={'A':{'B'},'B':{'A','C'},'C':{'B'}}  # Demo facility topology; no spatial certainty.
    zones={i.zone}|(adjacent.get(i.zone,set()) if body and body.include_adjacent else set())
    candidates=[p for p in candidates if p.zone in zones]
    candidates.sort(key=lambda p:(p.zone!=i.zone,p.direction=='unknown'))
    existing=list(db.scalars(select(Request).where(Request.incident==id)))
    seen={r.provider for r in existing}
    for p in candidates:
        if p.owner in seen or len(seen)>=10: continue
        if db.scalar(select(Block).where(((Block.owner==p.owner)&(Block.target==user.id))|((Block.owner==user.id)&(Block.target==p.owner)))): continue
        recent=list(db.scalars(select(Request).where(Request.provider==p.owner,Request.created>now()-86400)))
        if len(recent)>=5: continue
        db.add(Request(incident=id,provider=p.owner));seen.add(p.owner)
    try: db.flush();audit(db,user,'match_same_facility_floor_time',id);db.commit()
    except IntegrityError:
        db.rollback()  # Unique constraint makes concurrent/retried matching safe.
        if not db.scalar(select(Request).where(Request.incident==id)): raise
    return {'candidates':[request_view(db,r,user) for r in db.scalars(select(Request).where(Request.incident==id))],'notice':'목격 후보 수는 확보된 영상 수가 아닙니다. 같은 층에서 '+('사용자가 동의한 인접 구역까지 검색했습니다.' if body and body.include_adjacent else '같은 구역만 검색했습니다.')}
@app.get('/api/inbox')
def inbox(user=Depends(current),db:Session=Depends(getdb)):
    return [request_view(db,r,user) for r in db.scalars(select(Request).where(Request.provider==user.id))]

def provider_request(db,id,user):
    r=db.get(Request,id)
    if not r or r.provider!=user.id: fail(404,'요청을 찾을 수 없습니다')
    i=db.get(Incident,r.incident)
    if i.closed: fail(409,'종료된 사건입니다')
    return r,i
@app.post('/api/requests/{id}/respond')
def respond(id:str,body:ResponseInput,user=Depends(current),db:Session=Depends(getdb)):
    r,i=provider_request(db,id,user)
    if r.expires<=now(): fail(410,'요청이 만료됐습니다')
    if r.status not in ['pending','checking','review']: fail(409,'이미 처리한 요청입니다')
    r.status=body.status;audit(db,user,'response_'+body.status,id);db.commit();return {'status':r.status}

def sniff(data):
    if data.startswith(b'\x89PNG\r\n\x1a\n'):return 'image/png'
    if data.startswith(b'\xff\xd8\xff'):return 'image/jpeg'
    if len(data)>=12 and data[4:8]==b'ftyp':return 'video/mp4'
    fail(415,'PNG, JPEG 또는 MP4 공유본만 등록할 수 있습니다')
@app.post('/api/requests/{id}/evidence',status_code=201)
async def upload(id:str,file:UploadFile=File(...),kind:Literal['manual_share_copy','mock']=Form(...),user=Depends(current),db:Session=Depends(getdb)):
    r,i=provider_request(db,id,user)
    if r.expires<=now():fail(410,'요청이 만료됐습니다')
    if r.status not in ['pending','checking','review']: fail(409,'등록할 수 없는 상태입니다')
    if len(list(db.scalars(select(Evidence).where(Evidence.request==id))))>=3:fail(429,'요청별 최대 3개 파일입니다')
    data=await file.read(20*1024*1024+1)
    if len(data)>20*1024*1024:fail(413,'20MB 이내의 짧은 공유본을 등록하세요')
    mime=sniff(data); e=Evidence(request=id,hash=hashlib.sha256(data).hexdigest(),mime=mime,kind=kind)
    db.add(e);db.flush();path=FILES/e.id
    try:
        path.write_bytes(data);os.chmod(path,0o600);r.status='review';audit(db,user,'share_copy_uploaded_'+kind,e.id);db.commit()
    except Exception:
        path.unlink(missing_ok=True);raise
    return {'id':e.id,'sha256':e.hash,'approved':False,'kind':e.kind}
@app.get('/api/evidence/{id}/preview')
def preview(id:str,user=Depends(current),db:Session=Depends(getdb)):
    e=db.get(Evidence,id)
    if not e:fail(404,'자료가 없습니다')
    r=db.get(Request,e.request)
    if r.provider!=user.id:fail(404,'자료가 없습니다')
    audit(db,user,'owner_preview',id);db.commit();return FileResponse(FILES/id,media_type=e.mime)
@app.post('/api/requests/{id}/approve')
def approve(id:str,body:Approval,user=Depends(current),db:Session=Depends(getdb)):
    r,i=provider_request(db,id,user)
    if r.expires<=now():fail(410,'요청이 만료됐습니다')
    e=db.get(Evidence,body.evidence)
    if not e or e.request!=id:fail(404,'자료가 없습니다')
    if r.status!='review' or not body.reviewed_share_copy or not body.recipient_and_purpose_confirmed:fail(409,'공유본과 사건 요청자·목적을 확인한 뒤 승인하세요')
    e.approved=True;e.expires=now()+body.hours*3600;r.status='approved';audit(db,user,'approved_for_incident_requester',e.id);db.commit();return {'expires':e.expires,'recipient':'이 사건의 인증된 요청자','purpose':i.description}
@app.post('/api/requests/{id}/revoke')
def revoke(id:str,user=Depends(current),db:Session=Depends(getdb)):
    r,i=provider_request(db,id,user);r.status='revoked'
    for e in db.scalars(select(Evidence).where(Evidence.request==id)):e.approved=False
    audit(db,user,'revoked',id);db.commit();return {'ok':True}

def accessible(db,e,user):
    if not e:fail(404,'자료가 없습니다')
    r=db.get(Request,e.request);i=db.get(Incident,r.incident)
    if i.owner!=user.id:fail(404,'자료가 없습니다')
    if i.closed or r.status!='approved' or not e.approved or e.expires<=now():fail(403,'승인된 열람 기간이 아닙니다')
    return r,i
@app.post('/api/evidence/{id}/access')
def access(id:str,user=Depends(current),db:Session=Depends(getdb)):
    e=db.get(Evidence,id);accessible(db,e,user);token=secrets.token_urlsafe(32)
    db.add(Link(token=digest(token),evidence=id,owner=user.id,expires=min(now()+300,e.expires)));audit(db,user,'access_issued',id);db.commit()
    return {'token':token,'expires_in':min(300,int(e.expires-now()))}
@app.get('/api/evidence/{id}/download')
def download(id:str,ticket:str,user=Depends(current),db:Session=Depends(getdb)):
    e=db.get(Evidence,id);accessible(db,e,user);link=db.get(Link,digest(ticket))
    if not link or link.owner!=user.id or link.evidence!=id or link.expires<=now():fail(403,'만료되었거나 유효하지 않은 접근 수단입니다')
    audit(db,user,'download',id);db.commit();return FileResponse(FILES/id,media_type=e.mime,filename='witness-share'+('.mp4' if e.mime=='video/mp4' else '.png' if e.mime=='image/png' else '.jpg'))
@app.post('/api/incidents/{id}/close')
def close(id:str,user=Depends(current),db:Session=Depends(getdb)):
    i=owned(db,Incident,id,user);i.closed=now();audit(db,user,'incident_closed',id);db.commit();return {'ok':True}
@app.post('/api/requests/{id}/block')
def block(id:str,user=Depends(current),db:Session=Depends(getdb)):
    r,i=provider_request(db,id,user)
    if not db.scalar(select(Block).where(Block.owner==user.id,Block.target==i.owner)): db.add(Block(owner=user.id,target=i.owner))
    r.status='rejected';audit(db,user,'blocked_requester',id);db.commit();return {'ok':True}
@app.post('/api/requests/{id}/report')
def report(id:str,user=Depends(current),db:Session=Depends(getdb)):
    r,i=provider_request(db,id,user);audit(db,user,'report_needs_operator_review',id);db.commit();return {'status':'recorded_for_operator_review'}
@app.get('/api/audit')
def audits(user=Depends(current),db:Session=Depends(getdb)):
    return [{'action':a.action,'subject':a.subject,'at':a.at} for a in db.scalars(select(Audit).where(Audit.actor==user.id).order_by(Audit.at.desc()).limit(100))]

@app.post('/api/incidents/{id}/photo',status_code=201)
async def damage_photo(id:str,file:UploadFile=File(...),user=Depends(current),db:Session=Depends(getdb)):
    i=owned(db,Incident,id,user)
    if i.closed:fail(409,'종료된 사건입니다')
    if len(list(db.scalars(select(Photo).where(Photo.incident==id))))>=3:fail(429,'사진은 최대 3장입니다')
    data=await file.read(5*1024*1024+1)
    if len(data)>5*1024*1024:fail(413,'사진은 5MB 이내여야 합니다')
    mime=sniff(data)
    if mime not in ['image/png','image/jpeg']:fail(415,'피해 사진은 PNG 또는 JPEG입니다')
    p=Photo(incident=id,mime=mime,hash=hashlib.sha256(data).hexdigest());db.add(p);db.flush()
    path=FILES/p.id
    try:
        path.write_bytes(data);os.chmod(path,0o600);audit(db,user,'private_damage_photo',p.id);db.commit()
    except Exception:
        path.unlink(missing_ok=True);raise
    return {'id':p.id,'sha256':p.hash,'shared':False}
@app.get('/api/incidents/{id}/photos')
def photos(id:str,user=Depends(current),db:Session=Depends(getdb)):
    owned(db,Incident,id,user)
    return [{'id':p.id,'sha256':p.hash} for p in db.scalars(select(Photo).where(Photo.incident==id))]
@app.get('/api/photos/{id}')
def photo(id:str,user=Depends(current),db:Session=Depends(getdb)):
    p=db.get(Photo,id)
    if not p:fail(404,'사진이 없습니다')
    owned(db,Incident,p.incident,user)
    return FileResponse(FILES/id,media_type=p.mime)

WEB=ROOT/'web' 
app.mount('/',StaticFiles(directory=WEB,html=True),name='web')
