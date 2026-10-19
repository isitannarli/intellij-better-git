package dev.bettergit.services

import dev.bettergit.core.hosting.avatar.AuthorAvatarClient
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

internal object AvatarImages {
    fun decode(bytes: ByteArray): BufferedImage? {
        if (bytes.isEmpty() || bytes.size > AuthorAvatarClient.MAX_IMAGE_BYTES) return null
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width !in 1..1024 || height !in 1..1024) return null
                val source = reader.read(0) ?: return null
                val image = BufferedImage(72, 72, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    val side = minOf(width, height)
                    val x = (width - side) / 2
                    val y = (height - side) / 2
                    graphics.drawImage(source, 0, 0, 72, 72, x, y, x + side, y + side, null)
                } finally { graphics.dispose() }
                return image
            } finally { reader.dispose() }
        }
    }
}
