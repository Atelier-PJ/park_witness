"""Explicit operator commands, never exposed as unauthenticated HTTP endpoints."""
import argparse
import json
import os
from .storage import store
from datetime import datetime, timezone
from sqlalchemy import select, delete
from .main import DB, User, Vehicle, Parking, Incident, Request, Evidence, Photo, Link, Login, Audit, IncidentDetails, EvidenceDetails, Notification, EmailVerification, FacilityLayout, FILES, password_hash, now

def seed():
    if os.getenv('ALLOW_DEMO_SEED')!='1': raise SystemExit('Set ALLOW_DEMO_SEED=1 explicitly. Synthetic accounts only; never run against production.')
    password=os.environ.get('DEMO_PASSWORD')
    if not password or len(password)<10: raise SystemExit('DEMO_PASSWORD with 10+ characters required')
    with DB() as db:
        t=now()
        for name,floor in [('requester','B2'),('witness','B2'),('other','B3')]:
            email=name+'@demo.local'
            existing=db.scalar(select(User).where(User.email==email))
            if existing:
                existing.password=password_hash(password)
                continue
            user=User(email=email,password=password_hash(password));db.add(user);db.flush()
            v=Vehicle(owner=user.id,label='가상 차량 '+name,verified=True,source='demo_fixture_not_real_ownership');db.add(v);db.flush()
            db.add(Parking(owner=user.id,vehicle=v.id,facility='DEMO-PARK',floor=floor,zone='B',direction='north',start=t-7200,end=t-600,observed=t-600,expires=t+70*3600,consent=True,source='mock'))
        db.commit()
    print('Synthetic requester/witness/other accounts ready. Video files are not seeded.')

def verify(vehicle_id, reference):
    if len(reference)<8:raise SystemExit('Supply a non-sensitive operator review reference (8+ characters).')
    with DB() as db:
        v=db.get(Vehicle,vehicle_id)
        if not v:raise SystemExit('Vehicle not found')
        v.verified=True;v.source='operator_review'
        db.add(Audit(actor='operator',action='vehicle_verified:'+reference,subject=vehicle_id));db.commit()
    print('Vehicle verification recorded by operator.')

def cleanup():
    with DB() as db:
        t=now();count=0
        db.execute(delete(Parking).where(Parking.expires<=t))
        db.execute(delete(Link).where(Link.expires<=t))
        db.execute(delete(Login).where(Login.expires<=t))
        db.execute(delete(EmailVerification).where(EmailVerification.expires<=t))
        for e in db.scalars(select(Evidence)):
            r=db.get(Request,e.request);i=db.get(Incident,r.incident)
            # Closed case data after 30 days; abandoned unapproved uploads after 30 days.
            if (i.closed and i.closed+30*86400<=t) or (not e.approved and e.created+30*86400<=t):
                store(FILES).delete(e.id)
                db.execute(delete(EvidenceDetails).where(EvidenceDetails.evidence==e.id))
                db.execute(delete(Link).where(Link.evidence==e.id));db.delete(e);count+=1
        for i in db.scalars(select(Incident).where(Incident.closed.is_not(None),Incident.closed<=t-30*86400)):
            for p in db.scalars(select(Photo).where(Photo.incident==i.id)):
                store(FILES).delete(p.id);db.delete(p)
            db.flush()
            ids=list(db.scalars(select(Request.id).where(Request.incident==i.id)))
            db.execute(delete(Notification).where(Notification.request.in_(ids)))
            db.execute(delete(IncidentDetails).where(IncidentDetails.incident==i.id))
            db.execute(delete(Request).where(Request.incident==i.id));db.delete(i)
        db.add(Audit(actor='system',action='retention_cleanup',subject=str(count)));db.commit()
    print(json.dumps({'deleted_files':count}))

if __name__=='__main__':
    parser=argparse.ArgumentParser();sub=parser.add_subparsers(dest='command',required=True)
    sub.add_parser('seed-demo');sub.add_parser('cleanup')
    layout=sub.add_parser('set-layout');layout.add_argument('facility');layout.add_argument('json_file')
    v=sub.add_parser('verify-vehicle');v.add_argument('vehicle');v.add_argument('--review-reference',required=True)
    args=parser.parse_args()
    if args.command=='seed-demo':seed()
    elif args.command=='cleanup':cleanup()
    elif args.command=='set-layout':
        from pathlib import Path
        values=json.loads(Path(args.json_file).read_text())
        if not isinstance(values,dict) or any(not isinstance(k,str) or not isinstance(v,list) or any(not isinstance(x,str) or x not in values for x in v) for k,v in values.items()):raise SystemExit('Layout must map zones to existing neighboring zones')
        if any(k not in values[x] for k,v in values.items() for x in v):raise SystemExit('Adjacency must be symmetric')
        with DB() as db:
            item=db.get(FacilityLayout,args.facility)
            if not item:item=FacilityLayout(facility=args.facility);db.add(item)
            item.adjacency_json=json.dumps(values);db.commit()
    else:verify(args.vehicle,args.review_reference)
