package com.parkwitness.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import java.time.Instant
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var source: VehicleSource
    private var allowed by mutableStateOf(false)
    private var signal by mutableStateOf("상태 확인 전")
    private val worker=Executors.newSingleThreadExecutor()
    private var taskEpoch=0L
    private var busy by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source=vehicleSource(application)
        val preferences=getSharedPreferences("draft",MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF12685C),background=Color(0xFFF4F6F4))) {
                var server by remember { mutableStateOf(preferences.getString("server","http://10.0.2.2:8000")!!) }
                var email by remember { mutableStateOf(preferences.getString("email","")!!) }
                var password by remember { mutableStateOf("") }
                var token by remember { mutableStateOf("") } // Kept in memory, never in preferences.
                var facility by remember { mutableStateOf(preferences.getString("facility","DEMO-PARK")!!) }
                var floor by remember { mutableStateOf(preferences.getString("floor","B2")!!) }
                var zone by remember { mutableStateOf(preferences.getString("zone","B")!!) }
                var consent by remember { mutableStateOf(false) }
                var vehicles by remember { mutableStateOf(JSONArray()) }
                var sessions by remember { mutableStateOf(JSONArray()) }
                var inbox by remember { mutableStateOf(JSONArray()) }
                var message by remember { mutableStateOf("휴대전화에서 사고 접수·공유본 등록을 진행하세요.") }
                fun persist() { preferences.edit().putString("server",server).putString("email",email).putString("facility",facility).putString("floor",floor).putString("zone",zone).apply() }
                fun perform(block: () -> Unit) {
                    if(!allowed || busy) return
                    persist();busy=true
                    val epoch=taskEpoch
                    worker.execute {
                        try { block() } catch(e: Exception) { runOnUiThread { if(epoch==taskEpoch) message=e.message ?: "연결 실패" } }
                        finally { runOnUiThread { if(epoch==taskEpoch) busy=false } }
                    }
                }
                fun refresh(api: WitnessApi) {
                    val newSessions=JSONArray(api.call("/api/sessions"));val newInbox=JSONArray(api.call("/api/inbox"))
                    runOnUiThread { sessions=newSessions;inbox=newInbox;message="요청함을 갱신했습니다. 후보는 실제 영상 보유를 뜻하지 않습니다." }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(28.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        Text("PARK WITNESS",style=MaterialTheme.typography.headlineLarge)
                        Text("함께 확인하는 주차 안전",style=MaterialTheme.typography.titleLarge)
                        Text(source.label);Text(signal)
                        if(!allowed) {
                            Text("주차·화면 사용 허용 상태를 확인할 수 없습니다. 상세 입력을 잠갔습니다. 작성 내용은 저장됩니다.")
                        } else {
                            Text("주차 참여와 요청 현황",style=MaterialTheme.typography.headlineSmall)
                            OutlinedTextField(server,{server=it;persist()},label={Text("서버 주소")},modifier=Modifier.fillMaxWidth())
                            OutlinedTextField(email,{email=it;persist()},label={Text("계정 이메일")},modifier=Modifier.fillMaxWidth())
                            OutlinedTextField(password,{password=it},label={Text("비밀번호")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                            Button(enabled=!busy,onClick={ perform {
                                val result=JSONObject(WitnessApi(server,"").call("/api/login","POST",JSONObject().put("email",email).put("password",password)))
                                val access=result.getString("token");val me=JSONObject(WitnessApi(server,access).call("/api/me"))
                                runOnUiThread { token=access;password="";vehicles=me.getJSONArray("vehicles") }
                                refresh(WitnessApi(server,access))
                            } }) { Text(if(busy) "연결 중…" else "로그인 · 현황 갱신") }
                            if(token.isNotBlank()) {
                                OutlinedTextField(facility,{facility=it;persist()},label={Text("시설 코드 · 수동 입력")},modifier=Modifier.fillMaxWidth())
                                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    OutlinedTextField(floor,{floor=it;persist()},label={Text("층")},modifier=Modifier.weight(1f))
                                    OutlinedTextField(zone,{zone=it;persist()},label={Text("구역")},modifier=Modifier.weight(1f))
                                }
                                Row { Checkbox(consent,{consent=it});Text("현재 위치를 확인했고 주차 세션 매칭에 참여합니다. 자료 제공은 별도 승인합니다.",Modifier.padding(top=12.dp)) }
                                Button(enabled=!busy&&consent&&vehicles.length()>0,onClick={ perform {
                                    val v=(0 until vehicles.length()).map { vehicles.getJSONObject(it) }.firstOrNull { it.optBoolean("verified") } ?: error("운영자가 확인한 차량이 필요합니다")
                                    WitnessApi(server,token).call("/api/sessions","POST",JSONObject().put("vehicle",v.getString("id")).put("facility",facility).put("floor",floor).put("zone",zone).put("start",Instant.now().toString()).put("direction","unknown").put("consent",true))
                                    refresh(WitnessApi(server,token))
                                } }) { Text("지금 주차 참여 등록 · 수동") }
                                Button(enabled=!busy,onClick={perform {refresh(WitnessApi(server,token))}}) { Text("요청함 다시 확인") }
                                for(index in 0 until sessions.length()) {
                                    val s=sessions.getJSONObject(index)
                                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                                        Text("${s.getString("facility")} · ${s.getString("floor")} · ${s.getString("zone")}")
                                        Text(if(s.isNull("end")) "종료 시각 미확인 · 전원 종료 후 자동 추적 없음" else "사용자 확인 종료")
                                        Row {
                                            TextButton(enabled=!busy,onClick={perform { WitnessApi(server,token).call("/api/sessions/${s.getString("id")}/end","POST");refresh(WitnessApi(server,token)) }}) {Text("출차 확인")}
                                            TextButton(enabled=!busy,onClick={perform { WitnessApi(server,token).call("/api/sessions/${s.getString("id")}/withdraw","POST");refresh(WitnessApi(server,token)) }}) {Text("참여 끄기")}
                                        }
                                    } }
                                }
                                Text("받은 목격 요청 ${inbox.length()}건",style=MaterialTheme.typography.titleLarge)
                                for(index in 0 until inbox.length()) {
                                    val r=inbox.getJSONObject(index)
                                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                                        Text("사건 ${r.getString("incident").take(8)} · ${r.getString("floor")} ${r.getString("zone")}")
                                        Text("상태: ${r.getString("status")} · 영상 확인/제공은 모바일 웹에서 진행")
                                    } }
                                }
                                Text("휴대전화에서 같은 서버 주소에 접속하세요. 파일은 직접 내보낸 공유본을 수동 등록합니다. 블랙박스·다른 차량 영상 자동 조회는 지원하지 않습니다.")
                            }
                            Text(message)
                        }
                    }
                }
            }
        }
    }
    override fun onStart() { super.onStart();source.start { value,text -> runOnUiThread { allowed=value;signal=text } } }
    override fun onStop() { taskEpoch++;busy=false;allowed=false;source.stop();super.onStop() }
    override fun onDestroy() { source.stop();worker.shutdownNow();super.onDestroy() }
}
