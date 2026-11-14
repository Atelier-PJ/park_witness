package com.parkwitness.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
            WitnessTheme {
                var server by remember { mutableStateOf(preferences.getString("server","http://10.0.2.2:8000")!!) }
                var email by remember { mutableStateOf(preferences.getString("email","")!!) }
                var password by remember { mutableStateOf("") }
                var token by remember { mutableStateOf("") } // Kept in memory, never in preferences.
                var facility by remember { mutableStateOf(preferences.getString("facility","")!!) }
                var floor by remember { mutableStateOf(preferences.getString("floor","")!!) }
                var zone by remember { mutableStateOf(preferences.getString("zone","")!!) }
                var consent by remember { mutableStateOf(false) }
                var selectedVehicle by remember { mutableStateOf("") }
                var direction by remember { mutableStateOf("unknown") }
                var settings by remember { mutableStateOf(false) }
                var vehicles by remember { mutableStateOf(JSONArray()) }
                var sessions by remember { mutableStateOf(JSONArray()) }
                var inbox by remember { mutableStateOf(JSONArray()) }
                var message by remember { mutableStateOf("휴대전화에서 사건 접수와 자료 공유를 이어갈 수 있어요.") }
                fun persist() { preferences.edit().putString("server",server).putString("email",email).putString("facility",facility).putString("floor",floor).putString("zone",zone).apply() }
                fun perform(block: (Long) -> Unit) {
                    if(!allowed || busy) return
                    persist();busy=true
                    val epoch=taskEpoch
                    worker.execute {
                        try { block(epoch) } catch(e: Exception) { runOnUiThread { if(epoch==taskEpoch) message=e.message ?: "연결 실패" } }
                        finally { runOnUiThread { if(epoch==taskEpoch) busy=false } }
                    }
                }
                fun refresh(api: WitnessApi,epoch:Long=taskEpoch) {
                    val newSessions=JSONArray(api.call("/api/sessions"));val newInbox=JSONArray(api.call("/api/inbox"))
                    runOnUiThread { if(epoch==taskEpoch&&allowed) { sessions=newSessions;inbox=newInbox;message="요청함을 갱신했습니다." } }
                }
                Surface(Modifier.fillMaxSize()) {
                    WitnessScaffold(allowed,source.label,signal) {
                        if(!allowed) {
                            Text("주차·화면 사용 허용 상태를 확인할 수 없습니다. 상세 입력을 잠갔습니다. 작성 내용은 저장됩니다.")
                        } else {
                            Text(if(token.isBlank()) "내 차를 위한 작은 연결" else "주차 참여와 요청 현황",style=MaterialTheme.typography.headlineSmall)
                            Text(if(token.isBlank()) "로그인하고 내 주차 기록과 받은 요청을 확인하세요." else "직접 확인한 주차 위치로 참여해주세요.",color=Color(0xFF61746C),style=MaterialTheme.typography.bodyMedium)
                            TextButton(onClick={settings=!settings}) {Text(if(settings) "연결 설정 접기 −" else "서버 연결 설정 +")}
                            if(settings) {OutlinedTextField(server,{server=it;persist()},label={Text("서버 주소")},singleLine=true,modifier=Modifier.fillMaxWidth());Text(source.label,style=MaterialTheme.typography.bodySmall)}
                            if(token.isBlank()) {
                            OutlinedTextField(email,{email=it;persist()},label={Text("이메일")},singleLine=true,modifier=Modifier.fillMaxWidth())
                            OutlinedTextField(password,{password=it},label={Text("비밀번호")},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                            Button(enabled=!busy&&email.isNotBlank()&&password.isNotBlank(),modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),onClick={ perform {
                                val epoch=it
                                val result=JSONObject(WitnessApi(server,"").call("/api/login","POST",JSONObject().put("email",email).put("password",password)))
                                val access=result.getString("token");val me=JSONObject(WitnessApi(server,access).call("/api/me"))
                                runOnUiThread { if(epoch==taskEpoch&&allowed) {token=access;password="";vehicles=me.getJSONArray("vehicles");selectedVehicle=(0 until vehicles.length()).map { vehicles.getJSONObject(it) }.firstOrNull { it.optBoolean("verified") }?.getString("id") ?: ""} }
                                refresh(WitnessApi(server,access),epoch)
                            } }) { Text(if(busy) "연결 중…" else if(token.isBlank()) "로그인하고 시작하기  →" else "계정 현황 갱신") }
                            }
                            if(token.isNotBlank()) {
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                    Text(email,style=MaterialTheme.typography.bodyMedium)
                                    TextButton(enabled=!busy,onClick={perform { epoch -> WitnessApi(server,token).call("/api/logout","POST");runOnUiThread {if(epoch==taskEpoch){token="";password="";facility="";floor="";zone="";direction="unknown";selectedVehicle="";preferences.edit().remove("facility").remove("floor").remove("zone").apply();sessions=JSONArray();inbox=JSONArray();vehicles=JSONArray();consent=false;message="로그아웃했습니다."}} }}) {Text("로그아웃")}
                                }
                                Text("참여할 차량",style=MaterialTheme.typography.titleMedium)
                                for(vehicleIndex in 0 until vehicles.length()) {
                                    val vehicle=vehicles.getJSONObject(vehicleIndex)
                                    if(vehicle.optBoolean("verified")) Row {
                                        RadioButton(selectedVehicle==vehicle.getString("id"),onClick={selectedVehicle=vehicle.getString("id")})
                                        Text(vehicle.getString("label"),Modifier.padding(top=12.dp))
                                    }
                                }
                                OutlinedButton(onClick={val values=listOf("unknown","north","east","south","west");direction=values[(values.indexOf(direction)+1)%values.size]}) {
                                    Text("촬영 방향: "+mapOf("unknown" to "미확인","north" to "북","east" to "동","south" to "남","west" to "서")[direction]+" · 변경")
                                }
                                OutlinedTextField(facility,{facility=it;persist()},label={Text("주차장 코드")},modifier=Modifier.fillMaxWidth())
                                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    OutlinedTextField(floor,{floor=it;persist()},label={Text("층")},modifier=Modifier.weight(1f))
                                    OutlinedTextField(zone,{zone=it;persist()},label={Text("구역")},modifier=Modifier.weight(1f))
                                }
                                Row { Checkbox(consent,{consent=it});Text("현재 위치를 확인했고 주차 세션 매칭에 참여합니다. 자료 제공은 별도 승인합니다.",Modifier.padding(top=12.dp)) }
                                Button(modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),enabled=!busy&&consent&&(0 until vehicles.length()).any { vehicles.getJSONObject(it).optBoolean("verified") }&&selectedVehicle.isNotBlank()&&facility.isNotBlank()&&floor.isNotBlank()&&zone.isNotBlank(),onClick={ perform {
                                    val v=(0 until vehicles.length()).map { vehicles.getJSONObject(it) }.firstOrNull { it.optBoolean("verified") && it.getString("id")==selectedVehicle } ?: error("운영자가 확인한 차량이 필요합니다")
                                    WitnessApi(server,token).call("/api/sessions","POST",JSONObject().put("vehicle",v.getString("id")).put("facility",facility).put("floor",floor).put("zone",zone).put("start",Instant.now().toString()).put("direction",direction).put("consent",true))
                                    refresh(WitnessApi(server,token),it)
                                } }) { Text("이 위치에서 참여하기  →") }
                                OutlinedButton(modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=!busy,onClick={perform {refresh(WitnessApi(server,token),it)}}) { Text("요청함 다시 확인") }
                                if(sessions.length()==0) Text("아직 주차 기록이 없어요. 위치를 확인하고 첫 참여를 시작해보세요.",color=Color(0xFF61746C))
                                for(index in 0 until sessions.length()) {
                                    val s=sessions.getJSONObject(index)
                                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                                        Text("${s.getString("facility")} · ${s.getString("floor")} · ${s.getString("zone")}")
                                        Text(if(!s.optBoolean("consent")) "참여를 종료한 세션" else if(s.isNull("end")) "주차 중 · 출차 확인 전" else "사용자가 출차를 확인했습니다")
                                        Row {
                                            if(s.isNull("end")) TextButton(enabled=!busy,onClick={perform { WitnessApi(server,token).call("/api/sessions/${s.getString("id")}/end","POST");refresh(WitnessApi(server,token),it) }}) {Text("출차 확인")}
                                            if(s.optBoolean("consent")) TextButton(enabled=!busy,onClick={perform { WitnessApi(server,token).call("/api/sessions/${s.getString("id")}/withdraw","POST");refresh(WitnessApi(server,token),it) }}) {Text("참여 끄기")}
                                        }
                                    } }
                                }
                                HorizontalDivider()
                                Text("받은 목격 요청 ${inbox.length()}건",style=MaterialTheme.typography.titleLarge)
                                if(inbox.length()==0) Text("아직 받은 요청이 없어요. 요청이 도착하면 이곳에서 확인할 수 있습니다.",color=Color(0xFF61746C))
                                for(index in 0 until inbox.length()) {
                                    val r=inbox.getJSONObject(index)
                                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                                        Text("사건 ${r.getString("incident").take(8)} · ${r.getString("floor")} ${r.getString("zone")}")
                                        Text("${requestStatus(r.optString("status"))} · 자료 확인과 공유는 휴대전화에서 진행")
                                    } }
                                }
                                Text("휴대전화에서 자료를 직접 등록하고, 내용을 확인한 뒤 공유를 승인할 수 있어요.")
                            }
                            if(message.isNotBlank()) Surface(color=Color(0xFFE7EFDF),shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {Text(message,Modifier.padding(16.dp),style=MaterialTheme.typography.bodyMedium)}
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

private fun requestStatus(status:String)=when(status){"pending"->"응답 대기";"checking"->"확인 예정";"review"->"공유 검토";"no_video"->"영상 없음";"rejected"->"거절";"approved"->"공유 승인";"revoked"->"승인 철회";"expired"->"요청 만료";else->"상태 확인 중"}
