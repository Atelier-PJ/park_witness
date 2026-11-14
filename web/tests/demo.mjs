import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const out='output/demo';await mkdir(out,{recursive:true});
const python=process.env.E2E_PYTHON||'python';
const server=spawn(python,['backend/tests/serve_e2e.py'],{stdio:['ignore','pipe','pipe']});
let browser,context,page,start;const scenes=[];
async function hold(ms=2500){await page.waitForTimeout(ms);}
async function chapter(id,title,body){scenes.push({id,title,body,time:(Date.now()-start)/1000});await hold(600);}
async function until(check){for(let i=0;i<100;i++){if(await check())return;await hold(100);}throw Error('Demo state did not settle');}
try{
 const base=await new Promise((resolve,reject)=>{let text='';const timer=setTimeout(()=>reject(Error('Server start timeout')),20000);server.stdout.on('data',data=>{text+=data;const match=text.match(/E2E_PORT=(\d+)/);if(match){clearTimeout(timer);resolve('http://127.0.0.1:'+match[1]);}});server.on('exit',code=>reject(Error('Server exit '+code)));});
 browser=await chromium.launch({channel:'chrome',headless:true,slowMo:450});
 context=await browser.newContext({viewport:{width:390,height:844},recordVideo:{dir:out,size:{width:390,height:844}},locale:'ko-KR',timezoneId:'Asia/Seoul',acceptDownloads:true,reducedMotion:'reduce'});
 page=await context.newPage();start=Date.now();
 await chapter('start','모바일에서 사건 접수','사건 접수와 자료 공유는 모바일에서 이용합니다.');
 await page.goto(base);await hold(4000);
 async function login(who){await page.getByLabel('이메일',{exact:true}).fill(who+'@e2e.local');await page.getByLabel('비밀번호',{exact:true}).fill('witness-e2e-password');await page.getByRole('button',{name:'로그인',exact:true}).click();await until(async()=>(await page.locator('#status').textContent()).startsWith('로그인했습니다.'));await hold(1500);}
 async function logout(){await page.getByRole('button',{name:'로그아웃',exact:true}).click();await page.locator('#auth').waitFor({state:'visible'});}
 await chapter('incident','01  사고 발견과 접수','주차장·층·구역과 시간 범위로 확인을 요청합니다.');
 await login('requester');const form=page.locator('#incident-form');
 await form.getByLabel('주차장 코드',{exact:true}).fill('E2E-PARK');await form.getByLabel('층',{exact:true}).fill('B2');await form.getByLabel('구역',{exact:true}).fill('B');await form.getByLabel('발견한 손상과 확인 목적').fill('오른쪽 문 손상을 발견했습니다. 해당 시간 접촉 여부를 확인하고 싶습니다.');await hold(3000);
 await form.getByRole('button',{name:'사건 등록',exact:true}).click();await until(async()=>await page.locator('#case-list article').count()===1);await hold(2500);
 await chapter('match','02  익명 목격 후보 찾기','같은 시설·층·시간이 겹치는 참여자에게 연결합니다.');
 await page.getByRole('button',{name:'목격 후보 찾기',exact:true}).click();await until(async()=>(await page.locator('#status').textContent()).includes('1명의 익명 후보'));await page.locator('#case-list').scrollIntoViewIfNeeded();await hold(5000);
 await chapter('inbox','03  목격자의 요청 확인','연락처 대신 사건의 시간·장소·목적을 확인합니다.');
 await logout();await login('witness');await page.locator('nav button[data-tab=inbox]').click();await hold(4000);
 await chapter('upload','04  사진·영상 올리기','해당 시간의 사진이나 영상을 선택합니다.');
 await page.getByRole('button',{name:'공유 검토',exact:true}).click();await hold(1200);
 await page.locator('#inbox-list input[name=processing_notes]').fill('재현용 테스트 영상 · 개인정보 없음');
 await page.locator('#inbox-list input[type=file]').setInputFiles('web/tests/fixtures/share-demo.mp4');await hold(2500);
 await page.getByRole('button',{name:'자료 등록 후 검토하기'}).click();await until(async()=>await page.locator('#inbox-list .request').count()===1);await hold(1800);
 await chapter('approve','05  자료 공유하기','미리보기와 수신자·목적·열람 기한을 확인합니다.');
 const share=page.locator('#inbox-list .request').first();await share.getByRole('button',{name:'내 공유본 미리보기'}).click();
 await until(()=>share.locator('video').evaluate(video=>video.readyState>=2));await share.locator('video').scrollIntoViewIfNeeded();await share.locator('video').evaluate(video=>video.play());await hold(6500);
 await share.locator('input[type=checkbox]').check();await share.locator('select').selectOption('6');await hold(3000);await share.getByRole('button',{name:'이 자료 공유하기'}).click();await until(async()=>await page.getByRole('button',{name:'공유 승인 철회'}).count()===1);await hold(3500);
 await chapter('receive','06  승인 자료 전달','로그인한 사건 요청자에게만 자료를 전달합니다.');
 await logout();await login('requester');await page.locator('#case-list').scrollIntoViewIfNeeded();await page.getByRole('button',{name:'승인 자료 열람·다운로드'}).waitFor();await hold(3500);
 const downloadWait=page.waitForEvent('download');await page.getByRole('button',{name:'승인 자료 열람·다운로드'}).click();const file=await downloadWait;assert.deepEqual(await readFile(await file.path()),await readFile('web/tests/fixtures/share-demo.mp4'));await hold(3000);
 await chapter('close','07  사건 종료와 접근 차단','사건을 종료하면 더 이상 자료에 접근할 수 없습니다.');
 await page.getByRole('button',{name:'사건 종료',exact:true}).click();await until(async()=>await page.getByRole('button',{name:'승인 자료 열람·다운로드'}).count()===0);await hold(5000);
 assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Mobile viewport overflow');
 scenes.push({id:'end',time:(Date.now()-start)/1000});
 await context.close();const video=await page.video().path();await writeFile(out+'/scenes.json',JSON.stringify({video,scenes},null,2));console.log('DEMO RECORDING PASS');
}catch(error){if(page){await writeFile(out+'/failure.txt',await page.locator('body').innerText());await page.screenshot({path:out+'/failure.png',fullPage:true});}throw error;}finally{if(context)await context.close();if(browser)await browser.close();server.kill('SIGTERM');}
