'use strict';
let token = '', me = null;
const $ = s => document.querySelector(s);
const stateNames = {pending:'응답 대기',checking:'확인 예정',review:'공유 검토',no_video:'영상 없음',rejected:'거절',approved:'공유 승인',revoked:'승인 철회',expired:'요청 만료'};
const escapeHtml = s => String(s ?? '').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const date = value => new Date(value*1000).toLocaleString('ko-KR');
const el = (tag, text, cls) => {const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
const message = (text,error=false) => {$('#status').textContent=text;$('#status').className=error?'error':'';};
async function api(path, options={}) {
  const headers={...(options.headers||{}),Authorization:`Bearer ${token}`};
  if(options.body && !(options.body instanceof FormData))headers['Content-Type']='application/json';
  const response=await fetch('/api'+path,{...options,headers});
  if(!response.ok){let info;try{info=await response.json();}catch{}throw Error(typeof info?.detail==='string'?info.detail:`요청 실패 (${response.status})`);}
  return options.blob?response.blob():response.json();
}
const post = (path,body) => api(path,{method:'POST',body:body instanceof FormData?body:body===undefined?undefined:JSON.stringify(body)});
function action(parent,text,fn,cls='') {
  const b=el('button',text,cls);b.type='button';b.addEventListener('click',async()=>{b.disabled=true;try{await fn();}catch(e){message(e.message,true);}finally{b.disabled=false;}});parent.append(b);return b;
}
async function login() {
  const f=$('#login-form');const credentials=Object.fromEntries(new FormData(f));
  token=(await post('/login',credentials)).token;f.password.value='';await enter();
}
async function enter() {
  me=await api('/me');document.body.classList.add('signed-in');document.querySelectorAll('nav button').forEach(n=>{n.classList.toggle('active',n.dataset.tab==='cases');n.setAttribute('aria-pressed',String(n.dataset.tab==='cases'));});document.querySelectorAll('.tab').forEach(n=>n.hidden=n.id!=='cases');$('#auth').hidden=true;$('#workspace').hidden=false;$('#account-email').textContent=me.email;
  $('#vehicle-status').textContent=me.vehicles.some(v=>v.verified)?'차량 확인 완료':'차량을 등록하고 확인을 완료해주세요';
  document.querySelectorAll('.vehicles').forEach(select=>{select.replaceChildren();for(const v of me.vehicles){const o=el('option',`${v.label} · ${v.source==='demo_fixture_not_real_ownership'?'가상 차량':v.verified?'확인 완료':'확인 대기'}`);o.value=v.id;o.disabled=!v.verified;select.append(o);}});
  await refresh();message('로그인했습니다. 내 사건과 받은 요청을 확인해보세요.');
}
$('#login-form').addEventListener('submit',async e=>{e.preventDefault();try{await login();}catch(err){message(err.message,true);}});
$('#register').addEventListener('click',async()=>{const f=$('#login-form');if(!f.reportValidity())return;try{await post('/register',Object.fromEntries(new FormData(f)));await login();}catch(e){message(e.message,true);}});
$('#logout').addEventListener('click',async()=>{try{await post('/logout');}finally{token='';me=null;document.body.classList.remove('signed-in');$('#workspace').hidden=true;$('#auth').hidden=false;$('#case-list').replaceChildren();$('#inbox-list').replaceChildren();$('#parking-list').replaceChildren();message('로그아웃했습니다.');}});
document.querySelectorAll('nav button').forEach(b=>b.addEventListener('click',()=>{document.querySelectorAll('nav button').forEach(n=>{n.classList.toggle('active',n===b);n.setAttribute('aria-pressed',String(n===b));});document.querySelectorAll('.tab').forEach(n=>n.hidden=n.id!==b.dataset.tab);}));
function formWindow(form){const values=Object.fromEntries(new FormData(form));values.start=new Date(values.start).toISOString();values.end=values.end?new Date(values.end).toISOString():null;return values;}
async function submit(form,fn) {const b=form.querySelector('button[type="submit"],button:not([type])');b.disabled=true;try{await fn();await refresh();}catch(e){message(e.message,true);}finally{b.disabled=false;}}
$('#incident-form').addEventListener('submit',e=>{e.preventDefault();submit(e.target,async()=>{const form=e.target;const values=formWindow(form);delete values.damage;const result=await post('/incidents',values);if(form.damage.files.length){const data=new FormData();data.append('file',form.damage.files[0]);await api(`/incidents/${result.id}/photo`,{method:'POST',body:data});}message('사건을 등록했습니다. 아래 사건에서 후보 찾기를 실행하세요.');});});
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
  $('#metric-cases').textContent=cases.filter(c=>!c.closed).length;$('#metric-inbox').textContent=inbox.filter(r=>!r.closed&&['pending','checking','review'].includes(r.status)).length;$('#metric-sessions').textContent=sessions.filter(s=>s.consent&&!s.end).length;renderCases(cases);renderInbox(inbox);renderParking(sessions);
}
function renderCases(cases) {
  const list=$('#case-list');list.replaceChildren();
  if(!cases.length)list.append(el('div','아직 등록한 사건이 없어요. 장소와 시간을 입력해 확인을 요청해보세요.','empty'));
  for(const c of cases){const card=el('article',undefined,'card');const top=el('div',undefined,'card-top');top.append(el('h3',`${c.facility} · ${c.floor} ${c.zone}`),el('span',c.closed?'종료':'진행 중','badge'));card.append(top,el('small',`사건 ${c.id.slice(0,8)}`),el('p',c.description));const actions=el('div',undefined,'actions');card.append(actions);
    if(!c.closed){action(actions,'목격 후보 찾기',async()=>{const result=await post(`/incidents/${c.id}/match`);message(`${result.candidates.length}명의 익명 후보 · ${result.notice}`);await refresh();});action(actions,'인접 구역까지 확대해 요청',async()=>{const result=await post(`/incidents/${c.id}/match`,{include_adjacent:true});message(`${result.candidates.length}명의 익명 후보 · ${result.notice}`);await refresh();},'secondary');action(actions,'사건 종료',async()=>{await post(`/incidents/${c.id}/close`);await refresh();message('사건을 종료하고 자료 접근을 차단했습니다.');},'secondary');}
    if(!c.requests.length)card.append(el('p','주변 참여자에게 확인을 요청해보세요.','muted'));
    for(const r of c.requests){const row=el('div',undefined,'request');requestSummary(row,r);
      for(const evidence of r.evidence){row.append(el('p',`${evidence.kind==='mock'?'모의 자료':'공유 자료'} · 열람 만료 ${date(evidence.expires)}`));action(row,'승인 자료 열람·다운로드',async()=>{const grant=await post(`/evidence/${evidence.id}/access`);const blob=await api(`/evidence/${evidence.id}/download?ticket=${encodeURIComponent(grant.token)}`,{blob:true});const a=el('a');const url=URL.createObjectURL(blob);a.href=url;a.download='witness-share'+(evidence.mime==='video/mp4'?'.mp4':evidence.mime==='image/png'?'.png':'.jpg');a.click();setTimeout(()=>URL.revokeObjectURL(url),30000);message('승인된 공유본을 전달했습니다. 다운로드된 사본의 회수는 보장되지 않습니다.');},'secondary');}
      card.append(row);
    }list.append(card);
  }
}
function renderInbox(requests) {
  const list=$('#inbox-list');list.replaceChildren();if(!requests.length)list.append(el('div','아직 받은 요청이 없어요. 새로운 요청은 이곳에서 확인할 수 있어요.','empty'));
  for(const r of requests){const card=el('article',undefined,'card');card.append(el('small',`사건 ${r.incident.slice(0,8)} · 인증된 사건 요청자에게만 제공`));requestSummary(card,r);
    const editable=!r.closed&&['pending','checking','review'].includes(r.status);const actions=el('div',undefined,'actions');card.append(actions);
    if(editable){for(const [status,label] of [['checking','확인 예정'],['review','공유 검토'],['no_video','영상 없음'],['rejected','거절']])action(actions,label,async()=>{await post(`/requests/${r.id}/respond`,{status});await refresh();},status==='rejected'?'danger':'secondary');
      const form=el('form',undefined,'upload');form.innerHTML='<strong>공유할 자료 등록</strong><p class="muted">관련 구간을 파일로 직접 등록해주세요. 얼굴·번호판 등 불필요한 개인정보를 가리고, 공유 전 내용을 확인해주세요.</p><input type="hidden" name="kind" value="manual_share_copy"><label>PNG · JPEG · MP4 / 최대 20MB<input type="file" name="file" accept="image/png,image/jpeg,video/mp4" required></label><button type="submit">자료 등록 후 검토하기</button>';
      form.addEventListener('submit',e=>{e.preventDefault();submit(form,async()=>{await api(`/requests/${r.id}/evidence`,{method:'POST',body:new FormData(form)});message('등록했습니다. 공유본을 열어 검토한 뒤 별도로 승인하세요.');});});card.append(form);
    }
    for(const evidence of r.evidence){const block=el('div',undefined,'request');block.append(el('span',evidence.kind==='mock'?'모의 자료':'공유 자료','badge '+(evidence.kind==='mock'?'mock':'')));let previewed=false;
      action(block,'내 공유본 미리보기',async()=>{const blob=await api(`/evidence/${evidence.id}/preview`,{blob:true});let target=block.querySelector('.preview');if(!target){target=el('div',undefined,'preview');block.append(target);}target.replaceChildren();const media=el(evidence.mime==='video/mp4'?'video':'img');if(media.tagName==='VIDEO')media.controls=true;else media.alt='제공자가 검토할 공유본';const url=URL.createObjectURL(blob);media.src=url;target.append(media);previewed=true;setTimeout(()=>URL.revokeObjectURL(url),300000);},'secondary');
      if(editable){const confirm=el('label',undefined,'check share-confirm');const checkbox=document.createElement('input');checkbox.type='checkbox';confirm.append(checkbox,el('span',`공유본을 검토했고, 이 사건의 인증된 요청자에게 위 목적의 확인을 위해 24시간 열람을 허용합니다.`));block.append(confirm);action(block,'이 자료의 개별 공유 승인',async()=>{if(!previewed||!checkbox.checked)throw Error('공유본을 미리 보고 수신자·목적을 확인해주세요.');await post(`/requests/${r.id}/approve`,{evidence:evidence.id,reviewed_share_copy:true,recipient_and_purpose_confirmed:true,hours:24});message('지정된 사건 요청자에게만 승인했습니다.');await refresh();});}
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
for(const form of [$('#incident-form'),$('#parking-form')]){form.start.value=localInput(new Date(Date.now()-3600000));if(form.id==='incident-form')form.end.value=localInput(new Date(Date.now()-1800000));}
