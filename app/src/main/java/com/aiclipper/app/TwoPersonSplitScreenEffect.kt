package com.aiclipper.app

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlRect
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ByteBufferGlEffect
import androidx.media3.effect.GlEffect
import com.google.common.util.concurrent.Futures
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Produces a genuine two-panel video frame from two tracked face positions.
 * The source frame is sampled twice, once around each participant, for every output frame.
 */
@UnstableApi
class TwoPersonSplitScreenEffect(
    private val samples: List<FaceLayoutSample>
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean) =
        ByteBufferGlEffect<ByteBuffer>(SplitProcessor(samples)).toGlShaderProgram(context, useHdr)

    private class SplitProcessor(samples: List<FaceLayoutSample>) :
        ByteBufferGlEffect.Processor<ByteBuffer> {
        private val timeline = samples.filter { it.faces.size >= 2 }.sortedBy { it.timeMs }
        private var sourceWidth = 0
        private var sourceHeight = 0
        private var sourceAspect = 16f / 9f

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            sourceWidth = inputWidth
            sourceHeight = inputHeight
            sourceAspect = timeline.firstOrNull()?.sourceAspectRatio ?: (inputWidth.toFloat() / inputHeight.toFloat().coerceAtLeast(1f))
            return Size(540, 960)
        }

        override fun getScaledRegion(presentationTimeUs: Long): GlRect =
            GlRect(sourceWidth, sourceHeight)

        override fun processImage(
            image: ByteBufferGlEffect.Image,
            presentationTimeUs: Long
        ) = Futures.immediateFuture(renderSplitFrame(image, presentationTimeUs))

        private fun renderSplitFrame(
            image: ByteBufferGlEffect.Image,
            presentationTimeUs: Long
        ): ByteBuffer {
            val selected = nearestSample(presentationTimeUs / 1000L)
            val leftFace = selected?.faces?.getOrNull(0) ?: FaceCenter(0.30f, 0.5f)
            val rightFace = selected?.faces?.getOrNull(1) ?: FaceCenter(0.70f, 0.5f)
            val output = ByteBuffer.allocateDirect(image.width * image.height * 4)
            val input = image.pixelBuffer
            val panelAspect = image.width.toFloat() / (image.height / 2f)
            val cropWidth: Float
            val cropHeight: Float
            if (sourceAspect >= panelAspect) {
                cropWidth = (panelAspect / sourceAspect).coerceIn(0.1f, 1f)
                cropHeight = 1f
            } else {
                cropWidth = 1f
                cropHeight = (sourceAspect / panelAspect).coerceIn(0.1f, 1f)
            }
            val halfHeight = image.height / 2
            for (y in 0 until image.height) {
                val isTopPanel = y >= halfHeight
                val face = if (isTopPanel) leftFace else rightFace
                val panelY = if (isTopPanel) y - halfHeight else y
                val panelV = panelY.toFloat() / halfHeight.toFloat().coerceAtLeast(1f)
                val centerY = 1f - face.y
                val sourceV = (centerY + (panelV - 0.5f) * cropHeight).coerceIn(0f, 0.99999f)
                val sourceY = (sourceV * image.height).toInt().coerceIn(0, image.height - 1)
                for (x in 0 until image.width) {
                    val panelU = x.toFloat() / image.width.toFloat()
                    val sourceU = (face.x + (panelU - 0.5f) * cropWidth).coerceIn(0f, 0.99999f)
                    val sourceX = (sourceU * image.width).toInt().coerceIn(0, image.width - 1)
                    val src = (sourceY * image.width + sourceX) * 4
                    val dst = (y * image.width + x) * 4
                    output.put(dst, input.get(src))
                    output.put(dst + 1, input.get(src + 1))
                    output.put(dst + 2, input.get(src + 2))
                    output.put(dst + 3, input.get(src + 3))
                }
            }
            output.position(0)
            return output
        }

        private fun nearestSample(timeMs: Long): FaceLayoutSample? =
            timeline.minByOrNull { kotlin.math.abs(it.timeMs - timeMs) }

        override fun finishProcessingAndBlend(
            outputFrame: GlTextureInfo,
            presentationTimeUs: Long,
            result: ByteBuffer
        ) {
            result.position(0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputFrame.texId)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0,
                outputFrame.width, outputFrame.height,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, result
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                throw IllegalStateException("Split-screen frame upload failed: GL error $error")
            }
        }

        override fun release() = Unit
    }
}
