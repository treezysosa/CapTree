package studio.retro.ai.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import studio.retro.ai.R

data class ConsoleColors(val shell:Color,val accent:Color,val pink:Color,val lcd:Color,val ink:Color)
val Cream=Color(0xFFF4E9D8)
val Navy=Color(0xFF24253C)
val Screen=Color(0xFF151827)
val PixelFont=FontFamily(Font(R.font.press_start))
val LocalConsole=staticCompositionLocalOf { ConsoleColors(Color(0xFF6555B7),Color(0xFF80C9DB),Color(0xFFE58BAF),Color(0xFFA8C45A),Navy) }
@Composable fun RetroTheme(theme:String,content:@Composable()->Unit) {
    val colors=when(theme) {
        "Glacier Blue"->ConsoleColors(Color(0xFF38768E),Color(0xFFACE8EE),Color(0xFF26455B),Color(0xFFCEEFE5),Navy)
        "Retro Green"->ConsoleColors(Color(0xFF667849),Color(0xFFD0DDA7),Color(0xFF424840),Color(0xFFA8C45A),Color(0xFF24321C))
        else->ConsoleColors(Color(0xFF6555B7),Color(0xFF80C9DB),Color(0xFFE58BAF),Color(0xFFA8C45A),Navy)
    }
    CompositionLocalProvider(LocalConsole provides colors) { MaterialTheme(colorScheme=darkColorScheme(primary=colors.accent,secondary=colors.pink,background=Screen,surface=Navy,onSurface=Cream,onBackground=Cream),content=content) }
}
@Composable fun PixelText(text:String,modifier:Modifier=Modifier,color:Color=Cream,size:Int=10) { Text(text,modifier,fontFamily=PixelFont,fontSize=size.sp,color=color,lineHeight=(size*1.7).sp) }
@Composable fun ConsolePanel(modifier:Modifier=Modifier,lcd:Boolean=false,content:@Composable ColumnScope.()->Unit) {
    val c=LocalConsole.current
    Column(modifier.shadow(5.dp,RoundedCornerShape(8.dp)).border(3.dp,if(lcd)Color(0xFF34304F) else c.accent.copy(alpha=0.4f),RoundedCornerShape(8.dp)).background(if(lcd)Screen else Navy,RoundedCornerShape(8.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
}
@Composable fun RaisedButton(text:String,onClick:()->Unit,modifier:Modifier=Modifier,color:Color=LocalConsole.current.shell,enabled:Boolean=true) {
    Button(onClick,modifier.padding(bottom=4.dp).shadow(if(enabled)4.dp else 0.dp,RoundedCornerShape(9.dp)),enabled=enabled,shape=RoundedCornerShape(9.dp),colors=ButtonDefaults.buttonColors(containerColor=color,contentColor=Cream),border=BorderStroke(1.dp,Color.White.copy(alpha=0.3f)),contentPadding=PaddingValues(horizontal=12.dp,vertical=12.dp)) { Text(text,fontWeight=FontWeight.Bold,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis) }
}
@Composable fun RetroToggle(label:String,value:Boolean,onChange:(Boolean)->Unit) { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text(label,Modifier.weight(1f),color=Cream,fontSize=14.sp); Switch(value,onChange,colors=SwitchDefaults.colors(checkedThumbColor=LocalConsole.current.lcd,checkedTrackColor=LocalConsole.current.shell)) } }
@Composable fun RetroSlider(label:String,value:Float,range:ClosedFloatingPointRange<Float>,steps:Int=0,onChange:(Float)->Unit) {
    Column { Text(label,color=LocalConsole.current.accent,fontSize=13.sp); Slider(value,onChange,valueRange=range,steps=steps,colors=SliderDefaults.colors(thumbColor=LocalConsole.current.pink,activeTrackColor=LocalConsole.current.lcd)) }
}
@Composable fun PixelProgress(percent:Int?,message:String) {
    val c=LocalConsole.current
    val phase by rememberInfiniteTransition(label="pixels").animateFloat(0f,1f,infiniteRepeatable(tween(1100,easing=LinearEasing)),label="phase")
    Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        PixelText(message.uppercase().take(70),color=c.lcd,size=9)
        Canvas(Modifier.fillMaxWidth().height(22.dp).border(2.dp,c.lcd)) {
            val block=size.width/20
            for(i in 0..19) if(if(percent!=null) i<percent.coerceIn(0,100)/5 else (i+(phase*20).toInt())%20<4)drawRect(c.lcd,Offset(i*block+3,4f),androidx.compose.ui.geometry.Size(block-5,size.height-8))
        }
        Text(if(percent!=null)"$percent%" else "Working · progress is not measurable yet",fontSize=12.sp,color=c.accent)
    }
}
@Composable fun PixelIcon(index:Int,color:Color,modifier:Modifier=Modifier) {
    val patterns=listOf(
        listOf("00011000","00111100","01111110","11011011","00011000","00011000","01111110","00000000"),
        listOf("11111100","10000101","10110111","10110111","10000101","11111100","00110000","01111000"),
        listOf("11111111","10000001","10100101","10000001","10011001","10111101","11111111","00000000"),
        listOf("11000011","11000110","00101100","00011000","00101100","11000110","11000011","00000000"),
        listOf("00010000","01010100","00111000","11111110","00111000","01010100","00010000","00000000"),
        listOf("11111111","10010001","11111111","10011001","10011101","10011001","11111111","10010001"))
    Canvas(modifier.size(32.dp)) { val step=size.width/8; patterns[index%6].forEachIndexed { y,row -> row.forEachIndexed { x,ch -> if(ch=='1')drawRect(color,Offset(x*step,y*step),androidx.compose.ui.geometry.Size(step,step)) } } }
}
@Composable fun DPad(onMove:(Int)->Unit) {
    Column(horizontalAlignment=Alignment.CenterHorizontally) {
        PadKey("▲") { onMove(-2) }
        Row(verticalAlignment=Alignment.CenterVertically) { PadKey("◀") { onMove(-1) }; Box(Modifier.size(30.dp).background(Color(0xFF34304F))); PadKey("▶") { onMove(1) } }
        PadKey("▼") { onMove(2) }
    }
}
@Composable private fun PadKey(label:String,click:()->Unit) { Box(Modifier.size(34.dp).shadow(2.dp,RoundedCornerShape(4.dp)).background(Navy,RoundedCornerShape(4.dp)).border(1.dp,Color.White.copy(alpha=0.15f),RoundedCornerShape(4.dp)).clickable(onClick=click),contentAlignment=Alignment.Center) { Text(label,color=Cream,fontSize=13.sp) } }
@Composable fun ConsoleControls(onMove:(Int)->Unit,onA:()->Unit,onB:()->Unit,onStart:()->Unit,onSelect:()->Unit) {
    val c=LocalConsole.current
    Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        DPad(onMove)
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            TextButton(onSelect) { PixelText("SELECT",size=7) }; TextButton(onStart) { PixelText("START",size=7) }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.padding(top=18.dp)) { CircleKey("B",c.pink,onB); Text("BACK",fontSize=8.sp,color=Cream) }
            Column(horizontalAlignment=Alignment.CenterHorizontally) { CircleKey("A",c.pink,onA); Text("CONFIRM",fontSize=8.sp,color=Cream) }
        }
    }
}
@Composable private fun CircleKey(label:String,color:Color,click:()->Unit) { Box(Modifier.size(48.dp).shadow(5.dp,CircleShape).background(color,CircleShape).border(2.dp,Color.White.copy(alpha=0.3f),CircleShape).clickable(onClick=click),contentAlignment=Alignment.Center) { Text(label,color=Cream,fontWeight=FontWeight.Black,fontSize=22.sp) } }
