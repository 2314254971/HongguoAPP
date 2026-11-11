// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.mobile.player

import com.hongguo.mobile.App
import com.hongguotv.core.Rendition
import com.hongguotv.core.RemoteVideo
import java.io.Closeable
import java.io.IOException

/** 已打开的一集：解密流 + 实际编码尺寸（用于竖屏按比例算高度）。 */
class OpenedStream(
    val video: RemoteVideo,
    val rendition: Rendition,
    val width: Int,
    val height: Int,
) : Closeable {
    override fun close() = video.close()
}

object PlaybackResolver {

    /** 手机解码器不支持的编码：这类档位直接跳过，避免必然失败的重试。 */
    private val UNSUPPORTED = setOf("bytevc2", "bvc2", "vvc", "h266")
    private val AVC = setOf("h264", "avc", "avc1", "avc3")

    /**
     * 优先 1080P 及以下的 h264（兼容性最好），其次同清晰度的 HEVC，再退到更低档。
     * 打开后读 MP4 头里的真实尺寸，拿不到就沿用接口给的宽高。
     */
    fun open(app: App, episodeId: String, maxQuality: Int = 1080): OpenedStream {
        val ladder = app.repo.streamLadderWithCompatibility(episodeId)
        val inRange = ladder.renditions.filter { it.quality in 1..maxQuality }
        val pool = (inRange.ifEmpty { ladder.renditions }).filter { rendition ->
            rendition.codec.lowercase() !in UNSUPPORTED
        }
        if (pool.isEmpty()) throw IOException("这一集只有 VVC 片源，手机暂时放不了，试试其他集")

        val order = pool.sortedWith(
            compareByDescending<Rendition> { it.quality }
                .thenByDescending { if (it.codec.lowercase() in AVC) 1 else 0 },
        )

        var last: Exception? = null
        for (rendition in order) {
            val video = RemoteVideo(app.http, rendition.toStreamInfo())
            try {
                video.prepare(256 * 1024)
                val track = video.trackInfo()
                val width = track?.width ?: rendition.width ?: 0
                val height = track?.height ?: rendition.height ?: 0
                return OpenedStream(video, rendition, width, height)
            } catch (problem: Exception) {
                video.close()
                last = problem
            }
        }
        throw last ?: IOException("播放地址解析失败")
    }
}
