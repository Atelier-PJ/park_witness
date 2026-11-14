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

private val Forest=Color(0xFF173F39)
private val Mint=Color(0xFFD6F0BF)
private val Muted=Color(0xFF61746C)

@Composable fun WitnessTheme(content:@Composable ()->Unit) {
    MaterialTheme(colorScheme=lightColorScheme(primary=Forest,onPrimary=Color.White,
        background=Color(0xFFF3F5EF),surface=Color.White,onSurface=Color(0xFF183C36),
        surfaceVariant=Color(0xFFEDF1E9),onSurfaceVariant=Muted,outline=Color(0xFF83968A)),
        typography=Typography(titleLarge=MaterialTheme.typography.titleLarge.copy(fontWeight=FontWeight.SemiBold),
            headlineLarge=MaterialTheme.typography.headlineLarge.copy(fontWeight=FontWeight.Bold,letterSpacing=(-1).sp)),
        shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(18.dp),large=RoundedCornerShape(26.dp)),content=content)
}

@Composable fun WitnessScaffold(allowed:Boolean,source:String,signal:String,content:@Composable ColumnScope.()->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide=maxWidth>=840.dp
        Column(Modifier.fillMaxSize().padding(if(wide)32.dp else 22.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(46.dp).background(Forest,RoundedCornerShape(15.dp)),contentAlignment=Alignment.Center) {Text("W",color=Mint,fontSize=27.sp,fontWeight=FontWeight.Bold)}
                    Column {Text("PARK WITNESS",fontWeight=FontWeight.Bold,fontSize=18.sp,letterSpacing=1.sp);Text("주차장 목격 요청",fontSize=12.sp,color=Muted)}
                }
                Surface(color=if(allowed)Color(0xFFE5EFDD) else Color(0xFFF7E9D5),shape=RoundedCornerShape(50)) {
                    Text(if(!allowed)"상태 확인 필요" else if(source.startsWith("DEMO"))"시연 모드" else "차량 연결",Modifier.padding(horizontal=16.dp,vertical=10.dp),fontSize=12.sp,color=Forest)
                }
            }
            if(wide) Row(horizontalArrangement=Arrangement.spacedBy(24.dp),verticalAlignment=Alignment.Top) {
                WelcomePanel(Modifier.weight(.85f),false)
                InputPanel(Modifier.weight(1.15f),content)
            } else {
                WelcomePanel(Modifier.fillMaxWidth(),true)
                InputPanel(Modifier.fillMaxWidth(),content)
            }
            Text("자료 공유는 휴대전화에서 이용할 수 있습니다.",color=Muted,fontSize=12.sp,lineHeight=20.sp)
            if(!allowed)Text(signal,color=Muted,fontSize=12.sp)
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
        Text("주차 중 생긴 일,\n목격자를 찾아보세요.",color=Color.White,fontSize=if(compact)26.sp else 32.sp,lineHeight=if(compact)37.sp else 45.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1).sp)
        Text("같은 주차장에 있던 운전자에게\n당시 상황을 물어볼 수 있습니다.",color=Color(0xFFC3D6C8),fontSize=15.sp,lineHeight=25.sp)
        if(!compact) ParkingIllustration(Modifier.fillMaxWidth().height(180.dp))
        HorizontalDivider(color=Color(0xFF426157))
        Text("주차 위치를 등록해두세요.",color=Mint,fontSize=12.sp)
        Text("근처에서 목격 요청이 오면 이곳에 표시됩니다.\n자료는 공유에 동의한 경우에만 전달됩니다.",color=Color(0xFFCBDBCD),fontSize=12.sp,lineHeight=20.sp)
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
