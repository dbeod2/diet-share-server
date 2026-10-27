package com.dietshare.storage

import org.springframework.stereotype.Component
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.ImageIO

class InvalidImageException(message: String) : RuntimeException(message)

@Component
class ImageProcessor {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Resizes so the longer side is at most [maxSide] px and re-encodes as JPEG. */
    fun thumbnail(bytes: ByteArray, maxSide: Int = 320): ByteArray {
        val source = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw InvalidImageException("Unsupported image format")
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(source.width, source.height))
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())

        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = target.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(source, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }

        val out = ByteArrayOutputStream()
        ImageIO.write(target, "jpg", out)
        return out.toByteArray()
    }
}
