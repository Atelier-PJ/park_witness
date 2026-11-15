package com.parkwitness.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Forest=Color(0xFF123B35)
private val Mint=Color(0xFFD7F56B)
private val Muted=Color(0xFF65746F)
private val DashBg=Color(0xFF0B1118)

@Composable fun WitnessTheme(content:@Composable ()->Unit) {
    MaterialTheme(colorScheme=lightColorScheme(primary=Forest,onPrimary=Color.White,
        secondary=Color(0xFFB6D850),onSecondary=Color(0xFF17241F),
        background=Color(0xFFF4F6F2),surface=Color.White,onSurface=Color(0xFF172522),
        surfaceVariant=Color(0xFFEEF2ED),onSurfaceVariant=Muted,outline=Color(0xFFD5DDD7)),
        typography=Typography(titleLarge=MaterialTheme.typography.titleLarge.copy(fontWeight=FontWeight.SemiBold),
            headlineLarge=MaterialTheme.typography.headlineLarge.copy(fontWeight=FontWeight.Bold,letterSpacing=(-1).sp)),
        shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(18.dp),large=RoundedCornerShape(26.dp)),content=content)
}

@Composable fun WitnessScaffold(allowed:Boolean,content:@Composable ColumnScope.()->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide=maxWidth>=840.dp
        if (allowed) {
            Column(Modifier.fillMaxSize().background(DashBg).padding(horizontal=if(wide)20.dp else 10.dp, vertical=if(wide)12.dp else 6.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().height(34.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Column(verticalArrangement=Arrangement.spacedBy(1.dp)) {
                        Text("Park Witness",color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=18.sp,letterSpacing=.1.sp)
                        Text("주차장 사고 확인",fontSize=11.sp,color=Color(0xFF98A4B1))
                    }
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(6.dp).background(Color(0xFF8EBF91),RoundedCornerShape(50)))
                        Text("주차 중",fontSize=11.sp,color=Color(0xFFB7C2CF),fontWeight=FontWeight.Medium)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(6.dp), content=content)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(if(wide)32.dp else 22.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(24.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(46.dp).background(Forest,RoundedCornerShape(15.dp)),contentAlignment=Alignment.Center) {Text("W",color=Mint,fontSize=27.sp,fontWeight=FontWeight.Bold)}
                        Column {Text("PARK WITNESS",fontWeight=FontWeight.Bold,fontSize=18.sp,letterSpacing=1.sp);Text("주차장 사고 확인",fontSize=12.sp,color=Muted)}
                    }
                    Surface(color=Color(0xFFF7E9D5),shape=RoundedCornerShape(50)) {
                        Text("상태 확인 필요",Modifier.padding(horizontal=16.dp,vertical=10.dp),fontSize=12.sp,color=Forest)
                    }
                }
                if(wide) Row(horizontalArrangement=Arrangement.spacedBy(24.dp),verticalAlignment=Alignment.Top) {
                    WelcomePanel(Modifier.weight(.85f),false)
                    InputPanel(Modifier.weight(1.15f),content)
                } else {
                    WelcomePanel(Modifier.fillMaxWidth(),true)
                    InputPanel(Modifier.fillMaxWidth(),content)
                }
                Text("자료는 제공자가 검토하고 승인한 경우에만 공유됩니다.",color=Muted,fontSize=12.sp,lineHeight=20.sp)
            }
        }
    }
}
@Composable private fun InputPanel(modifier:Modifier,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier,shape=RoundedCornerShape(26.dp),color=Color.White,border=androidx.compose.foundation.BorderStroke(1.dp,Color(0xFFDDE5DD))) {
        Column(Modifier.padding(28.dp),verticalArrangement=Arrangement.spacedBy(18.dp),content=content)
    }
}
@Composable private fun WelcomePanel(modifier:Modifier,compact:Boolean) {
    Column(modifier.background(Forest,RoundedCornerShape(26.dp)).padding(28.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Text("PARK WITNESS",color=Mint,fontSize=11.sp,letterSpacing=2.sp,fontWeight=FontWeight.Bold)
        Text("주차 중 차량 손상,\n주변 운전자와 확인해요.",color=Color.White,fontSize=if(compact)26.sp else 32.sp,lineHeight=if(compact)37.sp else 45.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1).sp)
        Text("사고 시각이 정확하지 않아도 괜찮아요.\n겹친 시간과 구역의 운전자에게 요청합니다.",color=Color(0xFFC3D6C8),fontSize=15.sp,lineHeight=25.sp)
        if(!compact) ParkingIllustration(Modifier.fillMaxWidth().height(180.dp))
        HorizontalDivider(color=Color(0xFF426157))
        Text("요청은 익명으로 전달됩니다.",color=Mint,fontSize=12.sp)
        Text("영상은 자동으로 수집하지 않아요.\n차주가 고르고 승인한 자료만 전달됩니다.",color=Color(0xFFCBDBCD),fontSize=12.sp,lineHeight=20.sp)
    }
}
@Composable private fun ParkingIllustration(modifier:Modifier) {
    Canvas(modifier) {
        val w=size.width;val h=size.height
        drawCircle(Color(0xFF2A5147),minOf(w*.37f,h*.47f),Offset(w*.5f,h*.5f))
        drawCircle(Color(0xFF5A7B64),minOf(w*.3f,h*.4f),Offset(w*.5f,h*.5f),style=Stroke(1.dp.toPx()))
        for(i in 0..3) {
            val x=w*(.13f+i*.205f)
            drawLine(Color(0xFF739077),Offset(x,h*.15f),Offset(x,h*.8f),2.dp.toPx())
            if(i<3)drawLine(Color(0xFF739077),Offset(x,h*.8f),Offset(x+w*.205f,h*.8f),2.dp.toPx())
        }
        val x=w*.36f;val y=h*.24f;val cw=w*.19f;val ch=h*.5f
        drawRoundRect(Mint,Offset(x,y),Size(cw,ch),androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))
        drawRoundRect(Forest,Offset(x+cw*.14f,y+ch*.18f),Size(cw*.72f,ch*.24f),androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
        drawRoundRect(Color(0xFF739D7B),Offset(x+cw*.15f,y+ch*.7f),Size(cw*.7f,ch*.12f),androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        drawCircle(Mint,6.dp.toPx(),Offset(w*.77f,h*.26f))
        drawCircle(Mint.copy(alpha=.3f),15.dp.toPx(),Offset(w*.77f,h*.26f),style=Stroke(1.dp.toPx()))
        drawLine(Mint.copy(alpha=.6f),Offset(x+cw,y+ch*.4f),Offset(w*.77f,h*.26f),1.5.dp.toPx())
    }
}
