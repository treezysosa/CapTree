package studio.retro.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.io.File

@Composable fun PhotoComparison(before: String, after: String) {
    var divider by remember(before, after) { mutableFloatStateOf(0.5f) }
    var zoom by remember(before, after) { mutableFloatStateOf(1f) }
    Column {
        Box(Modifier.fillMaxWidth().height(260.dp).background(Screen).clipToBounds().pointerInput(before, after) {
            detectHorizontalDragGestures { change, amount ->
                change.consume()
                divider = (divider + amount / size.width).coerceIn(0f, 1f)
            }
        }) {
            AsyncImage(File(before), "Original photo", Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom))
            Box(Modifier.fillMaxSize().drawWithContent { clipRect(left = size.width * divider) { this@drawWithContent.drawContent() } }) {
                AsyncImage(File(after), "Enhanced photo", Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom))
            }
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width * divider
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
                drawCircle(Color(0xFFE58BAF), 10.dp.toPx(), Offset(x, size.height / 2))
            }
            Text("BEFORE", Modifier.align(Alignment.TopStart).background(Navy).padding(5.dp), color = Cream, fontSize = 10.sp)
            Text("AFTER", Modifier.align(Alignment.TopEnd).background(Navy).padding(5.dp), color = Cream, fontSize = 10.sp)
        }
        RetroSlider("DRAG THE DIVIDER · ZOOM ${"%.1f".format(zoom)}×", zoom, 1f..5f) { zoom = it }
    }
}
