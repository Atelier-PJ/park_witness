import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdir, mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import assert from 'node:assert/strict';

const python=process.env.E2E_PYTHON||'python';
const server=spawn(python,['backend/tests/serve_e2e.py'],{stdio:['ignore','pipe','pipe']});
let browser;
const out='output/e2e';await mkdir(out,{recursive:true});
async function waitUntil(check,message){for(let i=0;i<100;i++){if(await check())return;await new Promise(r=>setTimeout(r,100));}throw Error(message);}
try{
  const base=await new Promise((resolve,reject)=>{let buffer='';const timer=setTimeout(()=>reject(Error('Test server did not start')),20000);server.stdout.on('data',b=>{buffer+=b;const match=buffer.match(/E2E_PORT=(\d+)/);if(match){clearTimeout(timer);resolve('http://127.0.0.1:'+match[1]);}});server.on('exit',code=>reject(Error('Server exited: '+code)));});
  browser=await chromium.launch({headless:true,executablePath:process.env.E2E_CHROMIUM||undefined});
  const context=await browser.newContext({viewport:{width:390,height:844},acceptDownloads:true});
  const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto(base);
  async function login(name){await page.getByLabel('이메일',{exact:true}).fill(name+'@e2e.local');await page.getByLabel('비밀번호',{exact:true}).fill('witness-e2e-password');await page.getByRole('button',{name:'로그인',exact:true}).click();await page.locator('#workspace').waitFor({state:'visible'});await waitUntil(async()=>await page.locator('#status').textContent()==='로그인했습니다.','Login did not finish');}
  async function logout(){await page.getByRole('button',{name:'로그아웃',exact:true}).click();await page.locator('#auth').waitFor({state:'visible'});}
  const png=Buffer.from(await (await import('node:util')).promisify((cb)=>{const p=spawn(python,['-c',"from PIL import Image; import io,sys; b=io.BytesIO(); Image.new('RGB',(160,90),'green').save(b,format='PNG');sys.stdout.buffer.write(b.getvalue())"]);const data=[];p.stdout.on('data',b=>data.push(b));p.on('exit',code=>code?cb(Error('fixture failed')):cb(null,Buffer.concat(data)));})());
  await login('requester');
  const form=page.locator('#incident-form');
  await form.getByLabel('주차장 코드',{exact:true}).fill('E2E-PARK');await form.getByLabel('층',{exact:true}).fill('B2');await form.getByLabel('구역',{exact:true}).fill('B');await form.getByLabel('발견한 손상과 확인 목적').fill('E2E 사진 재시도와 자료 제공 확인');
  await form.locator('input[type=file]').setInputFiles({name:'broken.png',mimeType:'image/png',buffer:Buffer.from('broken')});
  await form.getByRole('button',{name:'사건 등록',exact:true}).click();
  await page.getByRole('button',{name:'사진 다시 등록',exact:true}).waitFor();
  assert.equal(await page.locator('#case-list article').count(),1);
  await form.locator('input[type=file]').setInputFiles({name:'damage.png',mimeType:'image/png',buffer:png});await form.getByRole('button',{name:'사진 다시 등록',exact:true}).click();
  await waitUntil(async()=>await form.getByRole('button',{name:'사건 등록',exact:true}).isEnabled(),'Photo retry did not complete');
  assert.equal(await page.locator('#case-list article').count(),1);
  await page.getByRole('button',{name:'내 피해 사진 보기'}).click();await page.locator('.damage-photos img').waitFor();
  await waitUntil(()=>page.locator('.damage-photos img').evaluate(n=>n.complete&&n.naturalWidth>0),'Damage photo did not decode');
  await page.getByRole('button',{name:'목격 후보 찾기',exact:true}).click();await waitUntil(async()=>(await page.locator('#status').textContent()).includes('1명의 익명 후보'),'No witness');
  await logout();await login('witness');
  assert.equal(await form.getByLabel('발견한 손상과 확인 목적').inputValue(),'');
  assert.equal(await form.getByLabel('주차장 코드',{exact:true}).inputValue(),'');
  assert.equal(await page.locator('#metric-cases').textContent(),'0');
  await page.locator('nav button[data-tab=inbox]').click();
  async function upload(){await page.locator('#inbox-list input[type=file]').setInputFiles({name:'share.png',mimeType:'image/png',buffer:png});await page.getByRole('button',{name:'자료 등록 후 검토하기'}).click();}
  await upload();await waitUntil(async()=>await page.locator('#inbox-list .request').count()===1,'First upload failed');
  await upload();await waitUntil(async()=>await page.locator('#inbox-list .request').count()===2,'Second upload failed');
  for(let i=0;i<2;i++){
    const block=page.locator('#inbox-list .request').nth(i);
    // Clicking approve without decoding a preview must not authorize the file.
    await block.locator('input[type=checkbox]').check();await block.getByRole('button',{name:'이 자료 공유하기'}).click();
    await waitUntil(async()=>(await page.locator('#status').textContent()).includes('공유본을 미리 보고'),'Preview gate did not reject');
    await block.getByRole('button',{name:'내 공유본 미리보기'}).click();
    await waitUntil(()=>block.locator('img').evaluate(n=>n.complete&&n.naturalWidth>0),'Preview did not decode');
    await block.locator('select').selectOption('6');
    await block.getByRole('button',{name:'이 자료 공유하기'}).click();
    await waitUntil(async()=>await page.locator('#inbox-list .request').nth(i).getByRole('button',{name:'이 자료 공유하기'}).count()===0,'Approval did not finish');
  }
  await page.screenshot({path:out+'/two-files-approved.png',fullPage:true});
  await logout();await login('other');assert.equal(await page.locator('#case-list article').count(),0);
  await logout();await login('requester');
  assert.equal(await page.getByRole('button',{name:'승인 자료 열람·다운로드'}).count(),2);
  const downloadWait=page.waitForEvent('download');await page.getByRole('button',{name:'승인 자료 열람·다운로드'}).first().click();const downloaded=await downloadWait;assert.deepEqual(await readFile(await downloaded.path()),png);
  await page.getByRole('button',{name:'사건 종료',exact:true}).click();await waitUntil(async()=>await page.getByRole('button',{name:'승인 자료 열람·다운로드'}).count()===0,'Closed case still exposes downloads');
  await logout();await login('witness');await page.locator('nav button[data-tab=inbox]').click();assert.equal(await page.getByRole('button',{name:'이 자료 공유하기'}).count(),0);
  assert.deepEqual(errors,[]);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Mobile page overflows');
  console.log('E2E PASS: photo retry, private photo, anonymous match, account isolation, upload, decoded preview gate, two approvals, duration, download bytes, other-user isolation, close, mobile layout');
}finally{if(browser)await browser.close();server.kill('SIGTERM');}
