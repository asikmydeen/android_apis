package dev.asik.devicebridge.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [content] as a QR code. Encoding is done with zxing's QRCodeWriter into a
 * BitMatrix, then painted into an Android Bitmap that Compose can show. Foreground/
 * background come from the caller so it can match the glass palette.
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    foreground: Color = Color.Black,
    background: Color = Color.White,
) {
    val fgArgb = foreground.toArgb()
    val bgArgb = background.toArgb()
    // 512px matrix regardless of display size — sharp when scaled, cheap to encode.
    val bitmap = remember(content, fgArgb, bgArgb) {
        encodeQr(content, 512, fgArgb, bgArgb)
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Pairing QR code",
            modifier = modifier.size(size),
        )
    }
}

private fun encodeQr(content: String, px: Int, fg: Int, bg: Int): android.graphics.Bitmap? =
    runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix.get(x, y)) fg else bg
            }
        }
        android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }.getOrNull()
