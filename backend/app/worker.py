"""Run as a separate worker; retry opt-in notifications and scheduled retention."""
import logging
import os
import smtplib
import ssl
import time
from email.message import EmailMessage
from sqlalchemy import select
from .main import DB, Notification, NotificationPreference, EmailVerification, User, Request, Incident, Audit, now

log = logging.getLogger(__name__)


def send(message):
    with smtplib.SMTP(os.environ['SMTP_HOST'], int(os.getenv('SMTP_PORT', '587')), timeout=10) as smtp:
        if os.getenv('SMTP_STARTTLS', 'true').lower() == 'true':smtp.starttls(context=ssl.create_default_context())
        if os.getenv('SMTP_USER'):smtp.login(os.environ['SMTP_USER'],os.environ['SMTP_PASSWORD'])
        smtp.send_message(message)

def deliver_verifications():
    if not os.getenv('SMTP_HOST'):return 0
    delivered=0
    with DB() as db:
        for item in db.scalars(select(EmailVerification).where(EmailVerification.delivery_token.is_not(None),EmailVerification.expires>now(),EmailVerification.next_attempt<=now(),EmailVerification.attempts<5).with_for_update(skip_locked=True)):
            pref=db.get(NotificationPreference,item.owner)
            if not pref or not pref.email_enabled:item.delivery_token=None;continue
            item.attempts+=1
            try:
                message=EmailMessage();message['From']=os.environ['SMTP_FROM'];message['To']=db.get(User,item.owner).email
                message['Subject']='Park Witness · 이메일 알림 주소를 확인해주세요'
                message.set_content('알림을 신청하셨다면 20분 안에 다음 링크를 열고 같은 계정으로 로그인해주세요.\n'+os.environ['PUBLIC_WEB_URL'].rstrip('/')+'/#email-verification='+item.delivery_token+'\n신청하지 않았다면 이 메일을 무시해주세요.')
                send(message);item.delivery_token=None;delivered+=1
            except (OSError,smtplib.SMTPException,KeyError):item.next_attempt=now()+min(3600,60*2**item.attempts)
        db.commit()
    return delivered

def deliver_notifications():
    host = os.getenv('SMTP_HOST')
    if not host: return 0
    delivered = 0
    with DB() as db:
        # Multiple workers cannot send the same row concurrently on PostgreSQL.
        rows = list(db.scalars(select(Notification).where(Notification.sent.is_(None),
            Notification.next_attempt <= now(), Notification.attempts < 5)
            .with_for_update(skip_locked=True)))
        for item in rows:
            pref = db.get(NotificationPreference, item.owner)
            request = db.get(Request, item.request)
            incident = db.get(Incident, request.incident) if request else None
            if not pref or not pref.email_enabled or not pref.verified or not request or not incident or incident.closed or request.expires <= now():
                item.sent = now(); continue
            item.attempts += 1
            try:
                message = EmailMessage()
                message['From'] = os.environ['SMTP_FROM']
                message['To'] = db.get(User, item.owner).email
                message['Subject'] = 'Park Witness · 새로운 확인 요청이 도착했어요'
                message.set_content('새로운 목격 확인 요청이 있습니다. 로그인 후 받은 요청함에서 확인해주세요.\n'
                    + os.environ['PUBLIC_WEB_URL'].rstrip('/') + '/\n자료 제공은 직접 확인하고 승인할 때만 진행됩니다.')
                send(message)
                item.sent = now(); delivered += 1
                db.add(Audit(actor='system', action='notification_sent', subject=item.id))
            except (OSError, smtplib.SMTPException, KeyError):
                item.next_attempt = now() + min(3600, 60 * 2 ** item.attempts)
                # Do not log addresses, credentials or exception strings from the SMTP server.
                log.warning('Notification delivery failed; attempt %d', item.attempts)
                db.add(Audit(actor='system', action='notification_retry', subject=item.id))
        db.commit()
    return delivered


def run_once():
    from .manage import cleanup
    cleanup()
    return deliver_verifications()+deliver_notifications()


if __name__ == '__main__':
    logging.basicConfig(level=logging.INFO)
    while True:
        try: run_once()
        except Exception: log.exception('Maintenance cycle failed')
        time.sleep(60)
