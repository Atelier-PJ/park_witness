'use strict';
let token = '', me = null, authEpoch = 0;
let confirmationToken=new URLSearchParams(location.hash.slice(1)).get('email-verification');
if(confirmationToken)history.replaceState(null,'',location.pathname);
const mediaUrls = new Set();
function releaseMedia(){for(const url of mediaUrls)URL.revokeObjectURL(url);mediaUrls.clear();}
function resetForms(all=true){
  for(const form of document.querySelectorAll(all?'#workspace form':'#incident-form')){form.reset();for(const input of form.querySelectorAll('input,select,textarea')){input.disabled=false;input.readOnly=false;}}
  delete $('#incident-form').dataset.incidentId;delete $('#incident-form').dataset.clientKey;
  $('#incident-form button[type=submit]').textContent='사건 등록';
  initializeDates(all);
}
function clearSession(){
  authEpoch++;token='';me=null;releaseMedia();resetForms();
  for(const id of ['metric-cases','metric-inbox','metric-sessions'])$('#'+id).textContent='0';
  $('#account-email').textContent='';$('#vehicle-status').textContent='';$('#email-notifications').checked=false;message('');
  for(const list of ['case-list','inbox-list','parking-list'])$('#'+list).replaceChildren();
  document.querySelectorAll('.vehicles').forEach(n=>n.replaceChildren());
  document.body.classList.remove('signed-in');$('#workspace').hidden=true;$('#auth').hidden=false;$('#login-form').reset();
}

const $ = s => document.querySelector(s);
const stateNames = {pending:'응답 대기',checking:'확인 예정',review:'공유 검토',no_video:'영상 없음',rejected:'거절',approved:'공유 승인',revoked:'승인 철회',expired:'요청 만료'};
const escapeHtml = s => String(s ?? '').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const date = value => new Date(value*1000).toLocaleString('ko-KR');
const el = (tag, text, cls) => {const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
const message = (text,error=false) => {$('#status').textContent=text;$('#status').className=error?'error':'';};
async function api(path, options={}) {
  const epoch=authEpoch;
  const headers={...(options.headers||{}),Authorization:`Bearer ${token}`};
  if(options.body && !(options.body instanceof FormData))headers['Content-Type']='application/json';
  const response=await fetch('/api'+path,{...options,headers});
  if(epoch!==authEpoch)throw Error('계정이 변경됐습니다. 다시 확인해주세요.');
  if(response.status===401){clearSession();throw Error('로그인이 만료됐습니다. 다시 로그인해주세요.');}
  if(!response.ok){let info;try{info=await response.json();}catch{}throw Error(typeof info?.detail==='string'?info.detail:`요청 실패 (${response.status})`);}
  return options.blob?response.blob():response.json();
}
const post = (path,body) => api(path,{method:'POST',body:body instanceof FormData?body:body===undefined?undefined:JSON.stringify(body)});
function action(parent,text,fn,cls='') {
  const b=el('button',text,cls);b.type='button';b.addEventListener('click',async()=>{const epoch=authEpoch;b.disabled=true;try{await fn();}catch(e){if(epoch===authEpoch)message(e.message,true);}finally{b.disabled=false;}});parent.append(b);return b;
}
async function login() {
  const f=$('#login-form');const credentials=Object.fromEntries(new FormData(f));
  clearSession();const epoch=authEpoch;const result=await post('/login',credentials);if(epoch!==authEpoch)return;token=result.token;await enter();
}
async function enter() {
  me=await api('/me');document.body.classList.add('signed-in');document.querySelectorAll('nav button').forEach(n=>{n.classList.toggle('active',n.dataset.tab==='cases');n.setAttribute('aria-pressed',String(n.dataset.tab==='cases'));});document.querySelectorAll('.tab').forEach(n=>n.hidden=n.id!=='cases');$('#auth').hidden=true;$('#workspace').hidden=false;$('#account-email').textContent=me.email;
  $('#vehicle-status').textContent=me.vehicles.some(v=>v.verified)?'차량 확인 완료':'차량을 등록하고 확인을 완료해주세요';
  document.querySelectorAll('.vehicles').forEach(select=>{select.replaceChildren();for(const v of me.vehicles){const o=el('option',`${v.label} · ${v.source==='demo_fixture_not_real_ownership'?'가상 차량':v.verified?'확인 완료':'확인 대기'}`);o.value=v.id;o.disabled=!v.verified;select.append(o);}});
  if(confirmationToken){await post('/notifications/verify',{token:confirmationToken});confirmationToken=null;}
  const settings=await api('/notifications/settings');$('#email-notifications').checked=settings.email_enabled;$('#email-notifications').disabled=!settings.available;$('#email-notifications-status').textContent=settings.available?(settings.verified?'주소 확인 완료':'신청 후 이메일에서 주소를 확인해주세요.'):'이메일 알림 준비 중 · 받은 요청함에서 확인해주세요.';
  await refresh();message('로그인했습니다.');
}
$('#login-form').addEventListener('submit',async e=>{e.preventDefault();try{await login();}catch(err){message(err.message,true);}});
$('#register').addEventListener('click',async()=>{const f=$('#login-form');if(!f.reportValidity())return;try{await post('/register',Object.fromEntries(new FormData(f)));await login();}catch(e){message(e.message,true);}});
$('#logout').addEventListener('click',async()=>{const request=post('/logout');clearSession();const epoch=authEpoch;try{await request;}catch{}if(epoch===authEpoch&&!token)message('로그아웃했습니다.');});
document.querySelectorAll('nav button').forEach(b=>b.addEventListener('click',()=>{document.querySelectorAll('nav button').forEach(n=>{n.classList.toggle('active',n===b);n.setAttribute('aria-pressed',String(n===b));});document.querySelectorAll('.tab').forEach(n=>n.hidden=n.id!==b.dataset.tab);}));
function formWindow(form){const values=Object.fromEntries(new FormData(form));values.start=new Date(values.start).toISOString();values.end=values.end?new Date(values.end).toISOString():null;return values;}
async function submit(form,fn) {const epoch=authEpoch;const b=form.querySelector('button[type="submit"],button:not([type])');b.disabled=true;try{await fn();if(epoch===authEpoch&&token)await refresh();}catch(e){if(epoch===authEpoch)message(e.message,true);}finally{b.disabled=false;}}
$('#incident-form').addEventListener('submit',e=>{e.preventDefault();submit(e.target,async()=>{
  const form=e.target;
  if(!form.dataset.incidentId){
    const values=formWindow(form);delete values.damage;
    values.discovered=new Date(values.discovered).toISOString();
    values.client_key=form.dataset.clientKey||(form.dataset.clientKey=crypto.randomUUID());
    const result=await post('/incidents',values);form.dataset.incidentId=result.id;
    for(const field of form.querySelectorAll('input:not([type=file]),textarea'))field.readOnly=true;
    form.vehicle.disabled=true;
  }
  if(form.damage.files.length){
    const data=new FormData();data.append('file',form.damage.files[0]);
    try{await api(`/incidents/${form.dataset.incidentId}/photo`,{method:'POST',body:data});}
    catch(error){form.querySelector('button[type=submit]').textContent='사진 다시 등록';await refresh();throw Error('사건은 등록됐습니다. 사진만 다시 선택해 등록해주세요. '+error.message);}
  }
  resetForms(false);message('사건을 등록했습니다. 아래에서 목격 후보를 찾아보세요.');
});});
$('#reset-incident').addEventListener('click',()=>{resetForms(false);message('새 사건을 작성할 수 있습니다. 이미 등록된 사건은 아래에서 확인하세요.');});
$('#parking-form').addEventListener('submit',e=>{e.preventDefault();submit(e.target,async()=>{const values=formWindow(e.target);values.consent=e.target.consent.checked;await post('/sessions',values);message('주차 참여를 시작했습니다. 출차할 때 참여를 종료해주세요.');});});
$('#vehicle-form').addEventListener('submit',e=>{e.preventDefault();submit(e.target,async()=>{await post('/vehicles',Object.fromEntries(new FormData(e.target)));await enter();message('차량 등록을 요청했습니다. 확인이 완료되면 이용할 수 있어요.');});});
document.querySelectorAll('.refresh').forEach(b=>b.addEventListener('click',()=>refresh().catch(e=>message(e.message,true))));
function requestSummary(parent,r) {
  const top=el('div',undefined,'card-top');const badge=el('span',stateNames[r.status]||r.status,'badge');badge.dataset.status=r.status;top.append(el('strong',r.alias),badge);parent.append(top);
  parent.append(el('p',`${r.facility} · ${r.floor} · ${r.zone} / ${date(r.start)} ~ ${date(r.end)}`));
  parent.append(el('p',r.purpose));
}
async function refresh() {
  const viewerToken=token;
  const [cases,inbox,sessions]=await Promise.all([api('/incidents'),api('/inbox'),api('/sessions')]);
  if(token!==viewerToken)return;
  releaseMedia();
  $('#metric-cases').textContent=cases.filter(c=>!c.closed).length;$('#metric-inbox').textContent=inbox.filter(r=>!r.closed&&['pending','checking','review'].includes(r.status)).length;$('#metric-sessions').textContent=sessions.filter(s=>s.consent&&!s.end).length;renderCases(cases);renderInbox(inbox);renderParking(sessions);
}
function renderCases(cases) {
  const list=$('#case-list');list.replaceChildren();
  if(!cases.length)list.append(el('div','아직 등록한 사건이 없어요. 장소와 시간을 입력해 확인을 요청해보세요.','empty'));
  for(const c of cases){const card=el('article',undefined,'card');const top=el('div',undefined,'card-top');top.append(el('h3',`${c.facility} · ${c.floor} ${c.zone}`),el('span',c.closed?'종료':'진행 중','badge'));card.append(top,el('small',`사건 ${c.id.slice(0,8)}`),el('p',c.description));const actions=el('div',undefined,'actions');card.append(actions);
    if(!c.closed){action(actions,'목격 후보 찾기',async()=>{const result=await post(`/incidents/${c.id}/match`);message(`${result.candidates.length}명의 익명 후보 · ${result.notice}`);await refresh();});action(actions,'인접 구역까지 확대해 요청',async()=>{const result=await post(`/incidents/${c.id}/match`,{include_adjacent:true});message(`${result.candidates.length}명의 익명 후보 · ${result.notice}`);await refresh();},'secondary');action(actions,'사건 종료',async()=>{await post(`/incidents/${c.id}/close`);await refresh();message('사건을 종료하고 자료 접근을 차단했습니다.');},'secondary');}
    action(actions,'내 피해 사진 보기',async()=>{const photos=await api(`/incidents/${c.id}/photos`);let panel=card.querySelector('.damage-photos');if(!panel){panel=el('div',undefined,'damage-photos preview');card.append(panel);}panel.replaceChildren();if(!photos.length)panel.append(el('p','등록한 피해 사진이 없어요.'));for(const photo of photos){if(!photo.available){panel.append(el('p','이 사진은 더 이상 이용할 수 없습니다.'));continue;}const blob=await api(`/photos/${photo.id}`,{blob:true});const img=el('img');img.alt='내 피해 사진';const url=URL.createObjectURL(blob);mediaUrls.add(url);img.src=url;panel.append(img);}},'secondary');
    if(!c.requests.some(r=>r.evidence.some(e=>e.available)))card.append(el('p','아직 확보된 자료가 없어요. 요청 상태를 확인해주세요.','muted'));
    if(c.discovered)card.append(el('small',`손상 발견 ${date(c.discovered)}`));
    for(const r of c.requests){const row=el('div',undefined,'request');requestSummary(row,r);
      for(const evidence of r.evidence){if(!evidence.available){row.append(el('p','자료를 더 이상 이용할 수 없습니다.'));continue;}row.append(el('p',`${evidence.kind==='mock'?'모의 자료':'공유 자료'} · 열람 만료 ${date(evidence.expires)}`));action(row,'승인 자료 열람·다운로드',async()=>{const grant=await post(`/evidence/${evidence.id}/access`);const blob=await api(`/evidence/${evidence.id}/download?ticket=${encodeURIComponent(grant.token)}`,{blob:true});const a=el('a');const url=URL.createObjectURL(blob);a.href=url;a.download='witness-share'+(evidence.mime==='video/mp4'?'.mp4':evidence.mime==='image/png'?'.png':'.jpg');a.click();setTimeout(()=>URL.revokeObjectURL(url),30000);message('승인된 공유본을 전달했습니다. 다운로드된 사본의 회수는 보장되지 않습니다.');},'secondary');}
      card.append(row);
    }list.append(card);
  }
}
function renderInbox(requests) {
  const list=$('#inbox-list');list.replaceChildren();if(!requests.length)list.append(el('div','아직 받은 요청이 없어요. 새로운 요청은 이곳에서 확인할 수 있어요.','empty'));
  for(const r of requests){const card=el('article',undefined,'card');card.append(el('small',`사건 ${r.incident.slice(0,8)} · 인증된 사건 요청자에게만 제공`));requestSummary(card,r);
    const editable=r.can_share;const actions=el('div',undefined,'actions');card.append(actions);
    if(editable){if(r.status!=='approved')for(const [status,label] of [['checking','확인 예정'],['review','공유 검토'],['no_video','영상 없음'],['rejected','거절']])action(actions,label,async()=>{await post(`/requests/${r.id}/respond`,{status});await refresh();},status==='rejected'?'danger':'secondary');
      const form=el('form',undefined,'upload');form.innerHTML='<strong>공유할 자료 등록</strong><p class="muted">해당 시간의 사진이나 영상을 올려주세요. 얼굴과 번호판 등 불필요한 개인정보는 미리 가려주세요.</p><input type="hidden" name="kind" value="manual_share_copy"><label>편집·가림 처리 내용<input name="processing_notes" maxlength="1000" placeholder="예: 20초 구간 추출, 번호판 가림"></label><label>PNG · JPEG · MP4 / 최대 20MB<input type="file" name="file" accept="image/png,image/jpeg,video/mp4" required></label><button type="submit">자료 등록 후 검토하기</button>';
      form.addEventListener('submit',e=>{e.preventDefault();submit(form,async()=>{await api(`/requests/${r.id}/evidence`,{method:'POST',body:new FormData(form)});message('자료를 등록했습니다. 미리보기에서 내용을 확인한 뒤 공유할 수 있습니다.');});});card.append(form);
    }
    for(const evidence of r.evidence){const block=el('div',undefined,'request');block.append(el('span',evidence.kind==='mock'?'모의 자료':'공유 자료','badge '+(evidence.kind==='mock'?'mock':'')));let previewed=false;
      if(!evidence.available){block.append(el('p','자료를 더 이상 이용할 수 없습니다.'));card.append(block);continue;}
      if(evidence.processing_notes)block.append(el('p',evidence.processing_notes));
      action(block,'내 공유본 미리보기',async()=>{
        previewed=false;const epoch=authEpoch;const blob=await api(`/evidence/${evidence.id}/preview`,{blob:true});
        let target=block.querySelector('.preview');if(!target){target=el('div',undefined,'preview');block.append(target);}target.replaceChildren();
        const video=evidence.mime==='video/mp4';const media=el(video?'video':'img');if(video){media.controls=true;media.preload='auto';}else media.alt='제공자가 검토할 공유본';
        const url=URL.createObjectURL(blob);mediaUrls.add(url);target.append(media);
        try{await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('자료를 읽는 데 시간이 걸립니다. 다시 확인해주세요.')),15000);media.addEventListener(video?'loadeddata':'load',()=>{clearTimeout(timer);resolve();},{once:true});media.addEventListener('error',()=>{clearTimeout(timer);reject(Error('자료를 열 수 없습니다. 파일을 확인해주세요.'));},{once:true});media.src=url;});previewed=epoch===authEpoch;}
        catch(error){target.replaceChildren(el('p',error.message));throw error;}
      },'secondary');
      if(editable&&!evidence.approved){
        const confirm=el('label',undefined,'check share-confirm');const checkbox=document.createElement('input');checkbox.type='checkbox';
        confirm.append(checkbox,el('span','자료 내용을 확인했습니다. 이 사건을 접수한 사람에게 위 목적으로 공유하는 데 동의합니다.'));block.append(confirm);
        const duration=el('label','열람 허용 시간');const select=el('select');for(const hours of [1,6,24]){const option=el('option',`${hours}시간`);option.value=hours;select.append(option);}duration.append(select);block.append(duration);
        action(block,'이 자료 공유하기',async()=>{if(!previewed||!checkbox.checked)throw Error('공유본을 미리 보고 수신자·목적을 확인해주세요.');await post(`/requests/${r.id}/approve`,{evidence:evidence.id,reviewed_share_copy:true,recipient_and_purpose_confirmed:true,hours:Number(select.value)});message('이 사건을 접수한 사람에게 자료를 공유했습니다.');await refresh();});
      }

      card.append(block);
    }
    if(!r.closed&&r.status==='approved')action(actions,'공유 승인 철회',async()=>{await post(`/requests/${r.id}/revoke`);await refresh();message('추가 열람을 차단했습니다. 이미 받은 사본은 회수되지 않습니다.');},'danger');
    if(!r.closed){action(actions,'요청자 차단',async()=>{await post(`/requests/${r.id}/block`);await refresh();},'quiet');action(actions,'요청 신고',async()=>{await post(`/requests/${r.id}/report`);message('신고를 접수했습니다. 내용을 검토하겠습니다.');},'quiet');}list.append(card);
  }
}
function renderParking(sessions) {
  const list=$('#parking-list');list.replaceChildren();if(!sessions.length)list.append(el('div','아직 주차 기록이 없어요. 위치를 등록하고 참여를 시작해보세요.','empty'));
  for(const s of sessions){const card=el('article',undefined,'card');card.append(el('h3',`${s.facility} · ${s.floor} ${s.zone}`),el('span',s.source==='mock'?'모의 세션':'주차 기록','badge '+(s.source==='mock'?'mock':'')),el('p',`${date(s.start)} ~ ${s.end?date(s.end):'출차 확인 전'}`),el('small',`마지막 확인 ${date(s.last_observed)} · ${s.consent?'참여 중':'참여 철회'}`));const actions=el('div',undefined,'actions');if(!s.end)action(actions,'지금 출차 확인',async()=>{await post(`/sessions/${s.id}/end`);await refresh();},'secondary');if(s.consent)action(actions,'참여 끄기',async()=>{await post(`/sessions/${s.id}/withdraw`);await refresh();},'quiet');card.append(actions);list.append(card);}
}
function localInput(d){return new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,16);}
function initializeDates(all=true){for(const form of (all?[$('#incident-form'),$('#parking-form')]:[$('#incident-form')])){form.start.value=localInput(new Date(Date.now()-3600000));if(form.id==='incident-form'){form.end.value=localInput(new Date(Date.now()-1800000));form.discovered.value=localInput(new Date());}}}
initializeDates();
$('#email-notifications').addEventListener('change',async e=>{try{const result=await api('/notifications/settings',{method:'PUT',body:JSON.stringify({email_enabled:e.target.checked})});if(e.target.checked&&!result.verified){await post('/notifications/verification');message('이메일로 보낸 확인 링크를 열어 알림 신청을 완료해주세요.');}else message(e.target.checked?'이메일 알림을 켰습니다.':'이메일 알림을 껐습니다.');}catch(error){e.target.checked=!e.target.checked;message(error.message,true);}});
