package com.parkwitness.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

private enum class WitnessTab(val label: String) {
    HOME("홈"), CASES("사고 확인"), INBOX("받은 요청"), PARKING("주차 참여")
}

class MainActivity : ComponentActivity() {
    private lateinit var source: VehicleSource
    private var allowed by mutableStateOf(false)
    private var signal by mutableStateOf("상태 확인 전")
    private val worker = Executors.newSingleThreadExecutor()
    private var taskEpoch = 0L
    private var busy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = vehicleSource(application)
        val preferences = getSharedPreferences("draft", MODE_PRIVATE)
        setContent {
            WitnessTheme {
                var server by remember { mutableStateOf(preferences.getString("server", "http://10.0.2.2:8000")!!) }
                var email by remember { mutableStateOf(preferences.getString("email", "")!!) }
                var password by remember { mutableStateOf("") }
                var token by remember { mutableStateOf("") }
                var selectedVehicle by remember { mutableStateOf("") }
                var tab by remember { mutableStateOf(WitnessTab.HOME) }
                var showSampleVideoWall by remember { mutableStateOf(false) }
                var demoAutoLoginAttempted by remember { mutableStateOf(false) }
                var settings by remember { mutableStateOf(false) }
                var vehicles by remember { mutableStateOf(JSONArray()) }
                var incidents by remember { mutableStateOf(JSONArray()) }
                var sessions by remember { mutableStateOf(JSONArray()) }
                var inbox by remember { mutableStateOf(JSONArray()) }
                var message by remember { mutableStateOf("사고가 난 시간과 장소를 알려주면 주변 운전자에게 확인을 요청할 수 있어요.") }

                var reportFacility by remember { mutableStateOf(preferences.getString("reportFacility", "")!!) }
                var reportFloor by remember { mutableStateOf(preferences.getString("reportFloor", "")!!) }
                var reportZone by remember { mutableStateOf(preferences.getString("reportZone", "")!!) }
                var reportDescription by remember { mutableStateOf("") }
                var reportWindowHours by remember { mutableIntStateOf(2) }

                var parkingFacility by remember { mutableStateOf(preferences.getString("parkingFacility", "")!!) }
                var parkingFloor by remember { mutableStateOf(preferences.getString("parkingFloor", "")!!) }
                var parkingZone by remember { mutableStateOf(preferences.getString("parkingZone", "")!!) }
                var parkingConsent by remember { mutableStateOf(false) }
                var parkingDirection by remember { mutableStateOf("unknown") }

                fun persist() {
                    preferences.edit()
                        .putString("server", server)
                        .putString("email", email)
                        .putString("reportFacility", reportFacility)
                        .putString("reportFloor", reportFloor)
                        .putString("reportZone", reportZone)
                        .putString("parkingFacility", parkingFacility)
                        .putString("parkingFloor", parkingFloor)
                        .putString("parkingZone", parkingZone)
                        .apply()
                }

                fun perform(block: (Long) -> Unit) {
                    if (!allowed || busy) return
                    persist()
                    busy = true
                    val epoch = taskEpoch
                    worker.execute {
                        try {
                            block(epoch)
                        } catch (e: Exception) {
                            runOnUiThread { if (epoch == taskEpoch) message = e.message ?: "연결을 확인해주세요." }
                        } finally {
                            runOnUiThread { if (epoch == taskEpoch) busy = false }
                        }
                    }
                }

                fun refresh(api: WitnessApi, epoch: Long = taskEpoch) {
                    val newIncidents = JSONArray(api.call("/api/incidents"))
                    val newInbox = JSONArray(api.call("/api/inbox"))
                    val newSessions = JSONArray(api.call("/api/sessions"))
                    runOnUiThread {
                        if (epoch == taskEpoch && allowed) {
                            incidents = newIncidents
                            inbox = newInbox
                            sessions = newSessions
                            message = "진행 상황을 불러왔습니다."
                        }
                    }
                }

                LaunchedEffect(allowed) {
                    if (allowed && BuildConfig.FLAVOR == "demo" && token.isBlank() && !demoAutoLoginAttempted) {
                        demoAutoLoginAttempted = true
                        val demoEmail = "requester@demo.local"
                        val demoPassword = "park123456"
                        email = demoEmail
                        password = demoPassword
                        persist()
                        perform { epoch ->
                            val result = JSONObject(
                                WitnessApi(server, "").call(
                                    "/api/login", "POST",
                                    JSONObject().put("email", demoEmail).put("password", demoPassword)
                                )
                            )
                            val access = result.getString("token")
                            val me = JSONObject(WitnessApi(server, access).call("/api/me"))
                            val newVehicles = me.getJSONArray("vehicles")
                            val defaultVehicle = (0 until newVehicles.length())
                                .map { newVehicles.getJSONObject(it) }
                                .firstOrNull { it.optBoolean("verified") }
                                ?.optString("id").orEmpty()
                            runOnUiThread {
                                if (epoch == taskEpoch && allowed) {
                                    token = access
                                    password = ""
                                    vehicles = newVehicles
                                    selectedVehicle = defaultVehicle
                                }
                            }
                            refresh(WitnessApi(server, access), epoch)
                        }
                    }
                }

                val verifiedVehicles = (0 until vehicles.length())
                    .map { vehicles.getJSONObject(it) }
                    .filter { it.optBoolean("verified") }

                Surface(Modifier.fillMaxSize()) {
                    WitnessScaffold(allowed) {
                        if (!allowed) {
                            ParkedGate(signal)
                        } else {
                            if (token.isBlank()) GuestAccountBar(onLogin = { tab = WitnessTab.CASES })
                            else AccountBar(email = email, busy = busy, onLogout = {
                                val oldToken = token
                                perform { epoch ->
                                    WitnessApi(server, oldToken).call("/api/logout", "POST")
                                    runOnUiThread {
                                        if (epoch == taskEpoch) {
                                            token = ""
                                            password = ""
                                            vehicles = JSONArray()
                                            incidents = JSONArray()
                                            inbox = JSONArray()
                                            sessions = JSONArray()
                                            selectedVehicle = ""
                                            tab = WitnessTab.HOME
                                            message = "로그아웃했습니다."
                                        }
                                    }
                                }
                            })
                            if (token.isBlank() && tab != WitnessTab.HOME) {
                            LoginPanel(
                                busy = busy,
                                email = email,
                                password = password,
                                showDemoLogin = BuildConfig.FLAVOR == "demo",
                                onEmail = { email = it; persist() },
                                onPassword = { password = it },
                                onDemoFill = {
                                    email = "requester@demo.local"
                                    password = "park123456"
                                    persist()
                                },
                                onSettings = { settings = !settings },
                                settingsOpen = settings,
                                server = server,
                                onServer = { server = it; persist() },
                                sourceLabel = source.label,
                                onLogin = {
                                    perform { epoch ->
                                        val result = JSONObject(
                                            WitnessApi(server, "").call(
                                                "/api/login", "POST",
                                                JSONObject().put("email", email).put("password", password)
                                            )
                                        )
                                        val access = result.getString("token")
                                        val me = JSONObject(WitnessApi(server, access).call("/api/me"))
                                        val newVehicles = me.getJSONArray("vehicles")
                                        val defaultVehicle = (0 until newVehicles.length())
                                            .map { newVehicles.getJSONObject(it) }
                                            .firstOrNull { it.optBoolean("verified") }
                                            ?.optString("id").orEmpty()
                                        runOnUiThread {
                                            if (epoch == taskEpoch && allowed) {
                                                token = access
                                                password = ""
                                                vehicles = newVehicles
                                                selectedVehicle = defaultVehicle
                                            }
                                        }
                                        refresh(WitnessApi(server, access), epoch)
                                    }
                                }
                            )
                            } else {
                            when (tab) {
                                WitnessTab.HOME -> HomeDashboard(
                                    modifier = Modifier.weight(1f),
                                    loggedIn = token.isNotBlank(),
                                    incidents = incidents,
                                    inbox = inbox,
                                    sessions = sessions,
                                    onIncident = { tab = WitnessTab.CASES },
                                    onInbox = { tab = WitnessTab.INBOX },
                                    onParking = { tab = WitnessTab.PARKING },
                                    onPreviewVideo = { showSampleVideoWall = true }
                                )
                                WitnessTab.CASES -> IncidentScreen(
                                    modifier = Modifier.weight(1f),
                                    onPreviewVideo = { showSampleVideoWall = true },
                                    busy = busy,
                                    vehicles = verifiedVehicles,
                                    selectedVehicle = selectedVehicle,
                                    onSelectVehicle = { selectedVehicle = it },
                                    facility = reportFacility,
                                    onFacility = { reportFacility = it; persist() },
                                    floor = reportFloor,
                                    onFloor = { reportFloor = it; persist() },
                                    zone = reportZone,
                                    onZone = { reportZone = it; persist() },
                                    description = reportDescription,
                                    onDescription = { reportDescription = it },
                                    windowHours = reportWindowHours,
                                    onWindowHours = { reportWindowHours = it },
                                    incidents = incidents,
                                    message = message,
                                    onCreateIncident = {
                                        perform { epoch ->
                                            val discovered = Instant.now()
                                            val start = discovered.minusSeconds(reportWindowHours * 3600L)
                                            val body = JSONObject()
                                                .put("vehicle", selectedVehicle)
                                                .put("facility", reportFacility.trim())
                                                .put("floor", reportFloor.trim())
                                                .put("zone", reportZone.trim())
                                                .put("start", start.toString())
                                                .put("end", discovered.toString())
                                                .put("discovered", discovered.toString())
                                                .put("description", reportDescription.trim())
                                                .put("client_key", UUID.randomUUID().toString())
                                            WitnessApi(server, token).call("/api/incidents", "POST", body)
                                            refresh(WitnessApi(server, token), epoch)
                                            runOnUiThread {
                                                if (epoch == taskEpoch) message = "사고 정보를 등록했습니다. 아래에서 주변 운전자에게 확인을 요청하세요."
                                            }
                                        }
                                    },
                                    onMatch = { incidentId ->
                                        perform { epoch ->
                                            val result = JSONObject(
                                                WitnessApi(server, token).call("/api/incidents/$incidentId/match", "POST", JSONObject())
                                            )
                                            refresh(WitnessApi(server, token), epoch)
                                            val count = result.optJSONArray("candidates")?.length() ?: 0
                                            runOnUiThread {
                                                if (epoch == taskEpoch) message = "익명 확인 요청을 보냈습니다. 요청 ${count}건을 확인 중입니다."
                                            }
                                        }
                                    },
                                    onRefresh = {
                                        perform { refresh(WitnessApi(server, token), it) }
                                    }
                                )

                                WitnessTab.INBOX -> InboxScreen(
                                    modifier = Modifier.weight(1f),
                                    busy = busy,
                                    inbox = inbox,
                                    message = message,
                                    onRespond = { requestId, status ->
                                        perform { epoch ->
                                            WitnessApi(server, token).call(
                                                "/api/requests/$requestId/respond", "POST", JSONObject().put("status", status)
                                            )
                                            refresh(WitnessApi(server, token), epoch)
                                            runOnUiThread { if (epoch == taskEpoch) message = "요청에 답했습니다." }
                                        }
                                    },
                                    onRefresh = { perform { refresh(WitnessApi(server, token), it) } },
                                    onGoParking = { tab = WitnessTab.PARKING }
                                )

                                WitnessTab.PARKING -> ParkingScreen(
                                    modifier = Modifier.weight(1f),
                                    busy = busy,
                                    vehicles = verifiedVehicles,
                                    selectedVehicle = selectedVehicle,
                                    onSelectVehicle = { selectedVehicle = it },
                                    facility = parkingFacility,
                                    onFacility = { parkingFacility = it; persist() },
                                    floor = parkingFloor,
                                    onFloor = { parkingFloor = it; persist() },
                                    zone = parkingZone,
                                    onZone = { parkingZone = it; persist() },
                                    consent = parkingConsent,
                                    onConsent = { parkingConsent = it },
                                    direction = parkingDirection,
                                    onDirection = { parkingDirection = it },
                                    sessions = sessions,
                                    onRegister = {
                                        perform { epoch ->
                                            val body = JSONObject()
                                                .put("vehicle", selectedVehicle)
                                                .put("facility", parkingFacility.trim())
                                                .put("floor", parkingFloor.trim())
                                                .put("zone", parkingZone.trim())
                                                .put("start", Instant.now().toString())
                                                .put("direction", parkingDirection)
                                                .put("consent", true)
                                            WitnessApi(server, token).call("/api/sessions", "POST", body)
                                            refresh(WitnessApi(server, token), epoch)
                                            runOnUiThread { if (epoch == taskEpoch) message = "주차 참여를 등록했습니다." }
                                        }
                                    },
                                    onEnd = { sessionId ->
                                        perform { epoch ->
                                            WitnessApi(server, token).call("/api/sessions/$sessionId/end", "POST")
                                            refresh(WitnessApi(server, token), epoch)
                                            runOnUiThread { if (epoch == taskEpoch) message = "출차를 확인했습니다." }
                                        }
                                    },
                                    onWithdraw = { sessionId ->
                                        perform { epoch ->
                                            WitnessApi(server, token).call("/api/sessions/$sessionId/withdraw", "POST")
                                            refresh(WitnessApi(server, token), epoch)
                                            runOnUiThread { if (epoch == taskEpoch) message = "주차 참여를 중단했습니다." }
                                        }
                                    },
                                    onRefresh = { perform { refresh(WitnessApi(server, token), it) } }
                                )
                            }
                            }
                            TabBar(selected = tab, onSelect = { tab = it })
                            if (showSampleVideoWall) SampleVideoWall(onDismiss = { showSampleVideoWall = false })
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        source.start { value, text -> runOnUiThread { allowed = value; signal = text } }
    }

    override fun onStop() {
        taskEpoch++
        busy = false
        allowed = false
        source.stop()
        super.onStop()
    }

    override fun onDestroy() {
        source.stop()
        worker.shutdownNow()
        super.onDestroy()
    }
}

@Composable
private fun LoginPanel(
    busy: Boolean,
    email: String,
    password: String,
    showDemoLogin: Boolean,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onDemoFill: () -> Unit,
    onSettings: () -> Unit,
    settingsOpen: Boolean,
    server: String,
    onServer: (String) -> Unit,
    sourceLabel: String,
    onLogin: () -> Unit
) {
    SectionTitle("사고 확인을 시작해요", "시간과 장소가 겹친 운전자에게 익명으로 요청합니다.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("사고 접수 · 받은 요청 · 주차 참여", color = Color(0xFF98A4B1), fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSettings) { Text(if (settingsOpen) "연결 설정 접기" else "서버 연결 설정", color = Color(0xFFFFA453)) }
    }
    if (settingsOpen) {
        OutlinedTextField(server, onServer, label = { Text("서버 주소") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = dashboardTextFieldColors())
        Text(sourceLabel, color = Color(0xFF98A4B1), fontSize = 11.sp)
    }
    OutlinedTextField(email, onEmail, label = { Text("이메일") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = dashboardTextFieldColors())
    OutlinedTextField(password, onPassword, label = { Text("비밀번호") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), colors = dashboardTextFieldColors())
    if (showDemoLogin) {
        TextButton(onClick = onDemoFill, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
            Text("시연 계정 채우기  ·  requester / park123456", color = Color(0xFFFFA453), fontSize = 10.sp)
        }
    }
    Button(onClick = onLogin, enabled = !busy && email.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF963D), contentColor = Color(0xFF1B2026), disabledContainerColor = Color(0xFFB96C32), disabledContentColor = Color(0xFF1F252B))) {
        Text(if (busy) "연결 중…" else "로그인하고 사고 확인")
    }
    PrivacyNote("영상은 자동으로 수집하지 않아요.", "차주가 직접 확인하고 공유를 승인한 자료만 전달됩니다.")
}

@Composable
private fun AccountBar(email: String, busy: Boolean, onLogout: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(email, color = Color(0xFFBEC7D0), fontWeight = FontWeight.Medium, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        TextButton(enabled = !busy, onClick = onLogout, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) { Text("로그아웃", color = Color(0xFF98A4B1), fontSize = 11.sp) }
    }
}

@Composable
private fun GuestAccountBar(onLogin: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("게스트로 둘러보는 중", color = Color(0xFF98A4B1), fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onLogin, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
            Text("로그인", color = Color(0xFFFFA453), fontSize = 11.sp)
        }
    }
}

@Composable
private fun HomeDashboard(
    modifier: Modifier = Modifier,
    loggedIn: Boolean,
    incidents: JSONArray,
    inbox: JSONArray,
    sessions: JSONArray,
    onIncident: () -> Unit,
    onInbox: () -> Unit,
    onParking: () -> Unit,
    onPreviewVideo: () -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxWidth < 520.dp || maxHeight > 520.dp
        Column(
            Modifier.fillMaxSize().then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("주차장 사고 확인", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("사고를 발견했다면, 주변 차량 기록부터 확인해보세요.", color = Color(0xFF9AA7B4), fontSize = 10.sp)
                }
                Text("주차 중", color = Color(0xFF9FC9A6), fontSize = 10.sp)
            }
            if (!compact) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    HomeReportCard(Modifier.weight(1.12f), onIncident)
                    Column(Modifier.weight(.88f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        HomeSummaryCard("내 사고", if (loggedIn) "${incidents.length()}건" else "로그인 후 확인", "접수한 사고와 진행 상태", "▣", onIncident, Modifier.weight(1f))
                        HomeSummaryCard("받은 요청", if (loggedIn) "${inbox.length()}건" else "로그인 후 확인", "주변 운전자의 확인 요청", "◉", onInbox, Modifier.weight(1f))
                        val active = (0 until sessions.length()).mapNotNull { sessions.optJSONObject(it) }
                            .firstOrNull { it.isNull("end") && it.optString("status") != "withdrawn" }
                        HomeSummaryCard("주차 참여", if (!loggedIn) "로그인 후 확인" else if (active != null) "참여 중" else "미참여", if (active != null) active.optString("facility", "등록 위치") else "위치를 등록해 요청 받기", "⌖", onParking, Modifier.weight(1f))
                    }
                }
            } else {
                HomeReportCard(Modifier.fillMaxWidth().height(150.dp), onIncident)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    HomeSummaryCard("내 사고", if (loggedIn) "${incidents.length()}건" else "로그인", "접수 현황", "▣", onIncident, Modifier.weight(1f).height(88.dp))
                    HomeSummaryCard("받은 요청", if (loggedIn) "${inbox.length()}건" else "로그인", "주변 확인", "◉", onInbox, Modifier.weight(1f).height(88.dp))
                    HomeSummaryCard("주차 참여", if (!loggedIn) "로그인" else "확인", "참여 설정", "⌖", onParking, Modifier.weight(1f).height(88.dp))
                }
            }
            Surface(
                onClick = onPreviewVideo,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                color = Color(0xFF151D27), shape = RoundedCornerShape(11.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF283341))
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("▶", color = Color(0xFFFFA453), fontSize = 13.sp)
                    Spacer(Modifier.width(9.dp))
                    Text("주변 차량 영상 화면 미리보기", color = Color(0xFFE4E9EE), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text("시연용 예시", color = Color(0xFF8996A3), fontSize = 9.sp)
                    Spacer(Modifier.width(5.dp))
                    Text("›", color = Color(0xFF9AA7B4), fontSize = 16.sp)
                }
            }
            if (compact) Spacer(Modifier.height(1.dp))
        }
    }
}

@Composable
private fun HomeReportCard(modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier,
        color = Color(0xFF151D27), shape = RoundedCornerShape(15.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF34404D))
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 17.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("차량 손상을 발견했나요?", color = Color(0xFFF0F3F6), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("정확한 시각을 몰라도 괜찮아요.\n가능한 시간과 주차 구역을 알려주면\n주변 차량에 확인을 요청합니다.", color = Color(0xFFADB8C3), fontSize = 10.sp, lineHeight = 15.sp)
                Spacer(Modifier.height(2.dp))
                Surface(color = Color(0xFFFF963D), shape = RoundedCornerShape(8.dp)) {
                    Text("사고 확인 요청 시작  →", Modifier.padding(horizontal = 11.dp, vertical = 7.dp), color = Color(0xFF1B2026), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("영상은 차주가 검토하고 승인한 경우에만 공유돼요.", color = Color(0xFF8996A3), fontSize = 8.sp)
            }
            Spacer(Modifier.width(6.dp))
            ParkingRadarArtwork(Modifier.width(100.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun HomeSummaryCard(title: String, value: String, detail: String, symbol: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick, modifier = modifier.fillMaxWidth(),
        color = Color(0xFF151D27), shape = RoundedCornerShape(13.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF283341))
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(symbol, color = Color(0xFFFFA453), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Color(0xFF9AA7B4), fontSize = 9.sp)
                Text(value, color = Color(0xFFE9EDF1), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, color = Color(0xFF788593), fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("›", color = Color(0xFF7F8B98), fontSize = 16.sp)
        }
    }
}

@Composable
private fun TabBar(selected: WitnessTab, onSelect: (WitnessTab) -> Unit) {
    Surface(Modifier.fillMaxWidth().height(40.dp), color = Color(0xFF111922), shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26313D))) {
      Row(Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        WitnessTab.values().forEach { item ->
            val active = item == selected
            val symbol = when (item) { WitnessTab.HOME -> "⌂"; WitnessTab.CASES -> "▣"; WitnessTab.INBOX -> "◉"; WitnessTab.PARKING -> "⌖" }
            Surface(onClick = { onSelect(item) }, modifier = Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(9.dp), color = if (active) Color(0xFF252D36) else Color.Transparent) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(symbol, color = if (active) Color(0xFFFF963D) else Color(0xFF84909D), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text(item.label, fontSize = 9.sp, color = if (active) Color.White else Color(0xFF9AA5B0), fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
      }
    }
}

@Composable
private fun IncidentScreen(
    modifier: Modifier = Modifier,
    onPreviewVideo: () -> Unit,
    busy: Boolean,
    vehicles: List<JSONObject>,
    selectedVehicle: String,
    onSelectVehicle: (String) -> Unit,
    facility: String,
    onFacility: (String) -> Unit,
    floor: String,
    onFloor: (String) -> Unit,
    zone: String,
    onZone: (String) -> Unit,
    description: String,
    onDescription: (String) -> Unit,
    windowHours: Int,
    onWindowHours: (Int) -> Unit,
    incidents: JSONArray,
    message: String,
    onCreateIncident: () -> Unit,
    onMatch: (String) -> Unit,
    onRefresh: () -> Unit
) {
    BoxWithConstraints(modifier) {
        val split = maxWidth >= 520.dp && maxHeight < 520.dp
        IncidentDashboard(
            split = split,
            onPreviewVideo = onPreviewVideo,
            busy = busy,
            vehicles = vehicles,
            selectedVehicle = selectedVehicle,
            onSelectVehicle = onSelectVehicle,
            facility = facility,
            onFacility = onFacility,
            floor = floor,
            onFloor = onFloor,
            zone = zone,
            onZone = onZone,
            description = description,
            onDescription = onDescription,
            windowHours = windowHours,
            onWindowHours = onWindowHours,
            incidents = incidents,
            message = message,
            onCreateIncident = onCreateIncident,
            onMatch = onMatch,
            onRefresh = onRefresh
        )
    }
}

@Composable
private fun IncidentDashboard(
    split: Boolean,
    onPreviewVideo: () -> Unit,
    busy: Boolean,
    vehicles: List<JSONObject>,
    selectedVehicle: String,
    onSelectVehicle: (String) -> Unit,
    facility: String,
    onFacility: (String) -> Unit,
    floor: String,
    onFloor: (String) -> Unit,
    zone: String,
    onZone: (String) -> Unit,
    description: String,
    onDescription: (String) -> Unit,
    windowHours: Int,
    onWindowHours: (Int) -> Unit,
    incidents: JSONArray,
    message: String,
    onCreateIncident: () -> Unit,
    onMatch: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 33.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("주차 중 차량 손상, 주변 운전자와 확인하세요", color = Color.White, fontSize = if (split) 20.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!split) Text("사고 시각이 정확하지 않아도 괜찮아요. 겹치는 시간과 구역부터 찾아볼게요.", color = Color(0xFF98A4B1), fontSize = 10.sp, maxLines = 1)
            }
                Text("● 안전한 익명 요청", color = Color(0xFFB8C5D1), fontSize = 10.sp, maxLines = 1)
        }

        if (split) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    Modifier.weight(.96f).fillMaxHeight(),
                    shape = RoundedCornerShape(16.dp), color = Color(0xFF151D27),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF283341))
                ) {
                    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DashboardSectionTitle("01", "사고 장소와 시간")
                        Text("사고가 있었을 것 같은 범위", color = Color(0xFF9AA7B4), fontSize = 12.sp)
                        TimeWindowSelector(windowHours, onWindowHours, compact = true)
                        HorizontalDivider(color = Color(0xFF283341))
                        DashboardInput(facility, onFacility, "주차장 · 건물 이름", Modifier.fillMaxWidth(), compact = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DashboardInput(floor, onFloor, "층", Modifier.weight(.65f), compact = true)
                            DashboardInput(zone, onZone, "구역 · 주차 열", Modifier.weight(1f), compact = true)
                        }
                        ParkingRadarArtwork(Modifier.fillMaxWidth().weight(1f))
                        Text("정확한 시각을 몰라도 선택한 범위의 참여 차량을 확인합니다.", color = Color(0xFF8996A3), fontSize = 9.sp, lineHeight = 12.sp, maxLines = 2)
                    }
                }
                Surface(
                    Modifier.weight(1.04f).fillMaxHeight(),
                    shape = RoundedCornerShape(16.dp), color = Color(0xFF151D27),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF283341))
                ) {
                    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DashboardSectionTitle("02", "손상 내용과 요청")
                        Text("확인할 내 차량", color = Color(0xFF9AA7B4), fontSize = 12.sp)
                        if (vehicles.isEmpty()) {
                            Text("확인된 차량이 필요합니다. 차량 등록은 휴대전화에서 진행할 수 있어요.", color = Color(0xFFFFB368), fontSize = 11.sp, lineHeight = 15.sp)
                        } else {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                vehicles.forEach { vehicle ->
                                    val id = vehicle.optString("id")
                                    DashboardChoice(vehicle.optString("label", "내 차량"), selectedVehicle == id, { onSelectVehicle(id) }, compact = true)
                                }
                            }
                        }
                        DashboardInput(description, onDescription, "손상 위치와 확인하고 싶은 내용을 적어주세요", Modifier.fillMaxWidth(), compact = true, multiline = true)
                        DamagePhotoExample(compact = true)
                        Text("번호판·연락처는 적지 마세요. 영상은 차주 승인 후 공유돼요.", color = Color(0xFF8996A3), fontSize = 9.sp, lineHeight = 12.sp, maxLines = 2)
                        Spacer(Modifier.weight(1f))
                        DashboardActionButton(busy, onCreateIncident, selectedVehicle.isNotBlank() && facility.isNotBlank() && floor.isNotBlank() && zone.isNotBlank() && description.trim().length >= 3, compact = true)
                    }
                }
            }
        } else {
            Surface(
                Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(18.dp), color = Color(0xFF151D27),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF283341))
            ) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DashboardSectionTitle("신고 접수", "사고를 확인할 주변 차량을 찾아볼게요")
                    Text("사고가 있었을 것 같은 범위", color = Color(0xFF9AA7B4), fontSize = 10.sp)
                    TimeWindowSelector(windowHours, onWindowHours, compact = false)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DashboardInput(facility, onFacility, "주차장 · 건물 이름", Modifier.weight(1f))
                        DashboardInput(floor, onFloor, "층", Modifier.weight(.42f))
                        DashboardInput(zone, onZone, "구역 · 주차 열", Modifier.weight(.75f))
                    }
                    Text("내 차량", color = Color(0xFF9AA7B4), fontSize = 10.sp)
                    if (vehicles.isEmpty()) Text("확인된 차량이 필요합니다. 차량 등록은 휴대전화에서 진행해주세요.", color = Color(0xFFFFB368), fontSize = 10.sp)
                    else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        vehicles.forEach { vehicle ->
                            val id = vehicle.optString("id")
                            DashboardChoice(vehicle.optString("label", "내 차량"), selectedVehicle == id, { onSelectVehicle(id) }, compact = false)
                        }
                    }
                    DashboardInput(description, onDescription, "손상 위치와 확인하고 싶은 내용", Modifier.fillMaxWidth(), compact = false, multiline = true)
                    DamagePhotoExample(compact = false)
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("익명 요청 · 차주 승인 후 자료 공유", color = Color(0xFF9AA7B4), fontSize = 9.sp)
                        DashboardActionButton(busy, onCreateIncident, selectedVehicle.isNotBlank() && facility.isNotBlank() && floor.isNotBlank() && zone.isNotBlank() && description.trim().length >= 3, compact = false)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            if (incidents.length() > 0) {
                val latest = incidents.optJSONObject(0)
                Text("내 신고 ${incidents.length()}건  ·  ${latest?.optString("facility", "진행 상황 확인") ?: "진행 상황 확인"}", Modifier.weight(1f), color = Color(0xFFB9C4CE), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DashboardMiniButton("영상 예시", onPreviewVideo)
                    DashboardMiniButton("새로고침", onRefresh)
                    TextButton(enabled = !busy && latest != null, onClick = { latest?.let { onMatch(it.optString("id")) } }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                        Text(if (busy) "요청 중" else "주변 차량에 요청  ›", color = Color(0xFFFFA453), fontSize = 9.sp)
                    }
                }
            } else {
                Text(if (message.startsWith("사고가 난")) "영상은 자동 수집하지 않아요. 자료 보유자가 직접 검토합니다." else message, Modifier.weight(1f), color = Color(0xFF9AA7B4), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                DashboardMiniButton("영상 예시", onPreviewVideo)
            }
        }
    }
}

@Composable
private fun DamagePhotoExample(compact: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(if (compact) 58.dp else 66.dp),
        color = Color(0xFF111922), shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF283341))
    ) {
        Row(Modifier.fillMaxSize().padding(5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Image(
                painter = painterResource(R.drawable.damage_scratch_example),
                contentDescription = "차량 문에 난 얕은 긁힘 예시 사진",
                modifier = Modifier.width(if (compact) 74.dp else 88.dp).fillMaxHeight().clip(RoundedCornerShape(7.dp)),
                contentScale = ContentScale.Crop
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("기스 사진 예시", color = Color(0xFFE2E8EE), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text("설명용 이미지 · 제출 자료 아님", color = Color(0xFF8996A3), fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(color = Color(0xFF252D36), shape = RoundedCornerShape(6.dp)) {
                Text("예시", Modifier.padding(horizontal = 7.dp, vertical = 4.dp), color = Color(0xFFFFB36B), fontSize = 8.sp)
            }
        }
    }
}

@Composable
private fun DashboardSectionTitle(step: String, title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Surface(color = Color(0xFF3A2B21), shape = RoundedCornerShape(7.dp)) {
            Text(step, Modifier.padding(horizontal = 7.dp, vertical = 4.dp), color = Color(0xFFFFA453), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Column {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = Color(0xFF8996A3), fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun TimeWindowSelector(hours: Int, onSelect: (Int) -> Unit, compact: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf(2 to "2시간", 6 to "6시간", 24 to "24시간").forEach { (value, label) ->
            DashboardChoice(label, hours == value, { onSelect(value) }, compact, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DashboardChoice(label: String, selected: Boolean, onClick: () -> Unit, compact: Boolean, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(if (compact) 28.dp else 34.dp),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) Color(0xFFFF963D) else Color(0xFF202A35),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Color(0xFFFF963D) else Color(0xFF34414F))
    ) {
        Box(Modifier.padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) Color(0xFF1C2025) else Color(0xFFCFD6DE), fontSize = if (compact) 11.sp else 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

@Composable
private fun DashboardInput(value: String, onValue: (String) -> Unit, hint: String, modifier: Modifier = Modifier, compact: Boolean = false, multiline: Boolean = false) {
    val height = when { multiline && compact -> 48.dp; multiline -> 58.dp; compact -> 31.dp; else -> 40.dp }
    BasicTextField(
        value = value,
        onValueChange = onValue,
        modifier = modifier.height(height).background(Color(0xFF101720), RoundedCornerShape(9.dp)).padding(horizontal = 9.dp, vertical = if (multiline) 7.dp else 0.dp),
        textStyle = TextStyle(color = Color(0xFFF1F4F7), fontSize = if (compact) 12.sp else 13.sp, lineHeight = 16.sp),
        singleLine = !multiline,
        minLines = if (multiline) 2 else 1,
        maxLines = if (multiline) 3 else 1,
        cursorBrush = SolidColor(Color(0xFFFF963D)),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, color = Color(0xFF8996A3), fontSize = if (compact) 10.sp else 12.sp, lineHeight = 15.sp, maxLines = if (multiline) 2 else 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        }
    )
}

@Composable
private fun DashboardActionButton(busy: Boolean, onClick: () -> Unit, enabled: Boolean, compact: Boolean) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = (if (compact) Modifier.fillMaxWidth() else Modifier.widthIn(min = 148.dp)).height(if (compact) 40.dp else 44.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF963D), contentColor = Color(0xFF1B2026), disabledContainerColor = Color(0xFFB96C32), disabledContentColor = Color(0xFF1F252B))
    ) { Text(if (busy) "접수 중…" else "사고 신고하기  ›", fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
private fun DashboardMiniButton(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color(0xFF202A35), shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF354352))) {
        Text(label, Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = Color(0xFFFFA453), fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SampleVideoWall(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(.94f).fillMaxHeight(.92f),
            color = Color(0xFF0B1118), shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF34414F))
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("주변 차량 영상", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("여러 운전자가 공유를 승인하면 이렇게 모아볼 수 있어요", color = Color(0xFF9AA7B4), fontSize = 10.sp)
                    }
                    TextButton(onClick = onDismiss) { Text("닫기", color = Color(0xFFFFA453)) }
                }
                Surface(color = Color(0xFF2A2119), shape = RoundedCornerShape(8.dp)) {
                    Text("시연용 애니메이션 · 실제 주변 차량의 영상이 아닙니다", Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), color = Color(0xFFFFBD83), fontSize = 9.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CameraSampleTile("CAM 01", "B2 북측", 0, Modifier.weight(1f))
                        CameraSampleTile("CAM 02", "B2 동측", 1, Modifier.weight(1f))
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CameraSampleTile("CAM 03", "B2 서측", 2, Modifier.weight(1f))
                        CameraSampleTile("CAM 04", "B2 출입로", 3, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraSampleTile(camera: String, location: String, index: Int, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "camera-sample-$index")
    val progress by transition.animateFloat(
        initialValue = .12f,
        targetValue = .82f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 5200 + index * 700), repeatMode = RepeatMode.Reverse),
        label = "car-motion-$index"
    )
    Surface(modifier, color = Color(0xFF131B22), shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF34414F))) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(0xFF1C252D))
                for (stall in 0..5) {
                    val x = size.width * (.08f + stall * .18f)
                    drawLine(Color(0xFF66727C).copy(alpha = .52f), Offset(x, size.height * .22f), Offset(x, size.height * .92f), 1.dp.toPx())
                }
                drawLine(Color(0xFF84909A).copy(alpha = .4f), Offset(0f, size.height * .72f), Offset(size.width, size.height * .72f), 1.dp.toPx())
                val bodyWidth = size.width * .19f
                val bodyHeight = size.height * .29f
                val carCenterX = size.width * (.14f + progress * .70f)
                val carCenterY = size.height * if (index % 2 == 0) .55f else .42f
                val carTopLeft = Offset(carCenterX - bodyWidth / 2, carCenterY - bodyHeight / 2)
                val bodyColor = listOf(Color(0xFFD5DCE2), Color(0xFF8B9AAB), Color(0xFFC28D61), Color(0xFF779889))[index]
                drawRoundRect(bodyColor, carTopLeft, Size(bodyWidth, bodyHeight), CornerRadius(bodyHeight * .28f))
                drawRoundRect(Color(0xFF293641), Offset(carTopLeft.x + bodyWidth * .2f, carTopLeft.y + bodyHeight * .22f), Size(bodyWidth * .6f, bodyHeight * .56f), CornerRadius(bodyHeight * .14f))
                drawCircle(Color(0xFFF4D6A1), bodyHeight * .045f, Offset(carTopLeft.x + bodyWidth * .15f, carCenterY))
                drawCircle(Color(0xFFF4D6A1), bodyHeight * .045f, Offset(carTopLeft.x + bodyWidth * .85f, carCenterY))
            }
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = .56f)).padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$camera  ·  $location", Modifier.weight(1f), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("샘플", color = Color(0xFFFFAD68), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .56f)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("21:14:${10 + index * 3}", color = Color(0xFFE0E5EA), fontSize = 8.sp)
                Text("모션 예시", color = Color(0xFFB7C2CF), fontSize = 8.sp)
            }
        }
    }
}

@Composable
private fun ParkingRadarArtwork(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width * .52f, size.height * .53f)
        val radius = minOf(size.width * .24f, size.height * .42f)
        drawCircle(Color(0xFFFF963D).copy(alpha = .08f), radius * 1.55f, center)
        drawCircle(Color(0xFFFF963D).copy(alpha = .42f), radius * 1.2f, center, style = Stroke(1.dp.toPx()))
        drawCircle(Color(0xFF768697).copy(alpha = .55f), radius * 1.55f, center, style = Stroke(1.dp.toPx()))
        val carWidth = radius * .62f
        val carHeight = radius * 1.3f
        val car = Offset(center.x - carWidth / 2, center.y - carHeight / 2)
        drawRoundRect(Color(0xFFE5EAF0), car, Size(carWidth, carHeight), CornerRadius(7.dp.toPx()))
        drawRoundRect(Color(0xFF273543), Offset(car.x + carWidth * .14f, car.y + carHeight * .22f), Size(carWidth * .72f, carHeight * .28f), CornerRadius(4.dp.toPx()))
        drawLine(Color(0xFFFF963D).copy(alpha = .85f), Offset(car.x + carWidth, center.y), Offset(size.width * .82f, size.height * .22f), 1.5.dp.toPx())
        drawCircle(Color(0xFFFF963D), 4.dp.toPx(), Offset(size.width * .82f, size.height * .22f))
        for (i in 0..2) {
            val x = size.width * (.16f + i * .34f)
            drawLine(Color(0xFF44515E), Offset(x, size.height * .24f), Offset(x, size.height * .82f), 1.dp.toPx())
        }
    }
}

@Composable
private fun InboxScreen(
    modifier: Modifier = Modifier,
    busy: Boolean,
    inbox: JSONArray,
    message: String,
    onRespond: (String, String) -> Unit,
    onRefresh: () -> Unit,
    onGoParking: () -> Unit
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("받은 요청", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("영상이 없어도, 공유하지 않아도 응답할 수 있어요.", color = Color(0xFF9AA7B4), fontSize = 10.sp)
            }
            DashboardMiniButton(if (busy) "확인 중…" else "새로고침", onRefresh)
        }
        if (inbox.length() == 0) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    InboxEmptyGlyph()
                    Text("아직 받은 요청이 없습니다", color = Color(0xFFDDE3E9), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("새 요청이 도착하면 이 목록에 표시됩니다.", color = Color(0xFF8996A3), fontSize = 11.sp)
                    TextButton(onClick = onGoParking, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("주차 참여 설정", color = Color(0xFFB9C4CE), fontSize = 10.sp)
                    }
                }
            }
            if (message.isNotBlank() && message != "진행 상황을 불러왔습니다.") {
                Text(message, color = Color(0xFFB9C4CE), fontSize = 10.sp)
            }
        } else {
            for (index in 0 until inbox.length()) {
                val request = inbox.optJSONObject(index) ?: continue
                Surface(color = Color(0xFF151D27), shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF283341))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(request.optString("alias", "목격 요청"), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            StatusPill(requestStatus(request.optString("status")))
                        }
                        Text("${request.optString("facility")} · ${request.optString("floor")} ${request.optString("zone")}", color = Color(0xFFD8DEE5), fontSize = 11.sp)
                        Text(request.optString("purpose"), color = Color(0xFFB0BBC6), fontSize = 11.sp, lineHeight = 16.sp)
                        Text("공유 자료는 직접 검토하고 승인한 뒤 전달됩니다.", color = Color(0xFF8996A3), fontSize = 10.sp)
                        if (request.optString("status") in listOf("pending", "checking", "review")) {
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                InboxAction("확인 예정", busy) { onRespond(request.optString("id"), "checking") }
                                InboxAction("공유 검토", busy) { onRespond(request.optString("id"), "review") }
                                InboxAction("자료 없음", busy) { onRespond(request.optString("id"), "no_video") }
                            }
                        }
                    }
                }
            }
            if (message.isNotBlank() && message != "진행 상황을 불러왔습니다.") {
                Text(message, color = Color(0xFFB9C4CE), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun InboxEmptyGlyph() {
    Canvas(Modifier.size(34.dp)) {
        val stroke = 1.4.dp.toPx()
        val left = size.width * .18f
        val top = size.height * .16f
        val width = size.width * .64f
        val height = size.height * .68f
        drawRoundRect(Color(0xFF6F7C89), Offset(left, top), Size(width, height), CornerRadius(3.dp.toPx()), style = Stroke(stroke))
        drawLine(Color(0xFF6F7C89), Offset(left + width * .2f, top + height * .33f), Offset(left + width * .8f, top + height * .33f), stroke)
        drawLine(Color(0xFF6F7C89), Offset(left + width * .2f, top + height * .56f), Offset(left + width * .68f, top + height * .56f), stroke)
    }
}

@Composable
private fun InboxAction(label: String, busy: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        enabled = !busy,
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF485563)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8DEE5), disabledContentColor = Color(0xFF78838F)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    ) { Text(label, fontSize = 10.sp) }
}

@Composable
private fun ParkingScreen(
    modifier: Modifier = Modifier,
    busy: Boolean,
    vehicles: List<JSONObject>,
    selectedVehicle: String,
    onSelectVehicle: (String) -> Unit,
    facility: String,
    onFacility: (String) -> Unit,
    floor: String,
    onFloor: (String) -> Unit,
    zone: String,
    onZone: (String) -> Unit,
    consent: Boolean,
    onConsent: (Boolean) -> Unit,
    direction: String,
    onDirection: (String) -> Unit,
    sessions: JSONArray,
    onRegister: () -> Unit,
    onEnd: (String) -> Unit,
    onWithdraw: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("주차 참여", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("위치를 등록하면 같은 구역의 사고 확인 요청을 받을 수 있어요.", color = Color(0xFF9AA7B4), fontSize = 10.sp)
            }
            DashboardMiniButton(if (busy) "확인 중…" else "새로고침", onRefresh)
        }

        Surface(color = Color(0xFF151D27), shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF283341))) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(3.dp).height(28.dp).background(Color(0xFFFF963D), RoundedCornerShape(2.dp)))
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("주차 위치", color = Color(0xFFE7EBEF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("요청이 오면 차량 정보와 구역만 전달됩니다.", color = Color(0xFF8996A3), fontSize = 9.sp)
                    }
                }
                Text("내 차량", color = Color(0xFF9AA7B4), fontSize = 10.sp)
                if (vehicles.isEmpty()) {
                    Text("확인된 차량이 없습니다. 차량 등록은 휴대전화에서 진행해주세요.", color = Color(0xFFFFB368), fontSize = 10.sp, lineHeight = 14.sp)
                } else {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        vehicles.forEach { vehicle ->
                            val id = vehicle.optString("id")
                            DashboardChoice(vehicle.optString("label", "내 차량"), selectedVehicle == id, { onSelectVehicle(id) }, compact = true)
                        }
                    }
                }
                DashboardInput(facility, onFacility, "주차장 · 건물 이름", Modifier.fillMaxWidth(), compact = true)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    DashboardInput(floor, onFloor, "층", Modifier.weight(.7f), compact = true)
                    DashboardInput(zone, onZone, "구역 · 주차 열", Modifier.weight(1f), compact = true)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("unknown" to "방향 미확인", "north" to "북", "east" to "동", "south" to "남", "west" to "서").forEach { (value, label) ->
                        DashboardChoice(label, direction == value, { onDirection(value) }, compact = true)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = consent,
                        onCheckedChange = onConsent,
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFF963D), uncheckedColor = Color(0xFF8996A3), checkmarkColor = Color(0xFF1B2026))
                    )
                    Text("이 위치를 사고 확인 요청 매칭에 사용합니다.", color = Color(0xFFB9C4CE), fontSize = 10.sp)
                }
                Button(
                    enabled = !busy && vehicles.any { it.optString("id") == selectedVehicle } && facility.isNotBlank() && floor.isNotBlank() && zone.isNotBlank() && consent,
                    onClick = onRegister,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF963D), contentColor = Color(0xFF1B2026), disabledContainerColor = Color(0xFFB96C32), disabledContentColor = Color(0xFF1F252B))
                ) { Text(if (busy) "등록 중…" else "주차 참여 등록", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("등록한 주차", Modifier.weight(1f), color = Color(0xFFE7EBEF), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        }
        if (sessions.length() == 0) {
            Text("등록된 주차 위치가 없습니다.", color = Color(0xFF8996A3), fontSize = 10.sp, modifier = Modifier.padding(start = 2.dp))
        }
        for (index in 0 until sessions.length()) {
            val session = sessions.optJSONObject(index) ?: continue
            Surface(color = Color(0xFF151D27), shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF283341))) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${session.optString("facility")} · ${session.optString("floor")} ${session.optString("zone")}", color = Color(0xFFE7EBEF), fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                        StatusPill(if (session.optBoolean("consent")) "참여 중" else "중지됨")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (session.isNull("end")) InboxAction("출차 확인", busy) { onEnd(session.optString("id")) }
                        if (session.optBoolean("consent")) InboxAction("참여 중지", busy) { onWithdraw(session.optString("id")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleChoices(vehicles: List<JSONObject>, selectedVehicle: String, onSelect: (String) -> Unit) {
    if (vehicles.isEmpty()) {
        Text("확인된 차량이 없습니다. 차량 등록은 휴대전화에서 진행해주세요.", color = Color(0xFF875744), fontSize = 11.sp)
        return
    }
    Text("내 차량", color = Color(0xFF61746C), fontSize = 11.sp)
    vehicles.forEach { vehicle ->
        val id = vehicle.optString("id")
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selectedVehicle == id, onClick = { onSelect(id) })
            Text(vehicle.optString("label", "내 차량"), fontSize = 11.sp)
        }
    }
}

@Composable
private fun ParkedGate(signal: String) {
    CardSurface {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("주차 상태를 확인하고 있어요", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("사고 확인과 주차 참여는 차량이 P이고 화면 사용이 허용된 상태에서 진행할 수 있습니다.", color = Color(0xFF61746C), fontSize = 12.sp, lineHeight = 18.sp)
            Text(signal, color = Color(0xFF875744), fontSize = 11.sp)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
        Text(subtitle, color = Color(0xFF98A4B1), fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun CardSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color(0xFFFBFCFA), shape = RoundedCornerShape(17.dp), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun InfoBanner(text: String) {
    Surface(color = Color(0xFFE7EFDF), shape = RoundedCornerShape(11.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), color = Color(0xFF315846), fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun PrivacyNote(title: String, body: String) {
    Surface(color = Color(0xFF17232D), shape = RoundedCornerShape(11.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF293847))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Color(0xFFFFA453), fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            Text(body, color = Color(0xFFB1BDC8), fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun dashboardTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color(0xFFF1F4F7),
    unfocusedTextColor = Color(0xFFF1F4F7),
    focusedContainerColor = Color(0xFF101720),
    unfocusedContainerColor = Color(0xFF101720),
    focusedBorderColor = Color(0xFFFF963D),
    unfocusedBorderColor = Color(0xFF34414F),
    focusedLabelColor = Color(0xFFFFA453),
    unfocusedLabelColor = Color(0xFF98A4B1),
    cursorColor = Color(0xFFFF963D)
)

@Composable
private fun EmptyCard(title: String, body: String) {
    CardSurface {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(body, color = Color(0xFF61746C), fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun StatusPill(label: String) {
    Surface(color = Color(0xFFE9F0E6), shape = RoundedCornerShape(50)) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color(0xFF49654F), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun requestStatus(status: String) = when (status) {
    "pending" -> "응답 대기"
    "checking" -> "확인 예정"
    "review" -> "공유 검토"
    "no_video" -> "자료 없음"
    "rejected" -> "응답 거절"
    "approved" -> "공유 승인"
    "revoked" -> "공유 철회"
    "expired" -> "요청 만료"
    else -> "상태 확인 중"
}
