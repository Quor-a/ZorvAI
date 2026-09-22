package com.yuanbao.miniapp.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import kotlin.math.max

/**
 * OpenGL ES 2.0 backend of the self-developed renderer.
 *
 * Everything is drawn with our own shaders:
 *  - solid / rounded rectangles are resolved analytically in the fragment shader
 *  - text is rendered from a self-built glyph atlas (generated with Canvas, uploaded as a texture)
 *  - images are uploaded as textures and drawn as textured quads
 *
 * The host picks this backend instead of [CanvasPainter] via RenderSurface.backend.
 */
class GLRenderer {

    private var programRect = 0
    private var programTex = 0
    private var viewportW = 0
    private var viewportH = 0

    private val vertRect = FloatArray(8)
    private var vertBuf: FloatBuffer = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** Glyph atlas: ASCII + common CJK rendered on demand. */
    private val atlasSize = 1024
    private var atlasBitmap: Bitmap? = null
    private var atlasCanvas: Bitmap? = null
    private var atlasCanvasObj: Canvas? = null
    private val atlasPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = ATLAS_FONT_SIZE.toFloat() }
    private val glyphRect = HashMap<Char, FloatArray>()   // char -> [u0,v0,u1,v1,w,h]
    private var atlasCursorX = 0
    private var atlasCursorY = 0
    private var atlasRowHeight = 0
    private var atlasTextureId = 0
    private var atlasDirty = true

    private val imageTextures = HashMap<String, Int>()
    var imageProvider: ((src: String) -> Bitmap?)? = null

    companion object {
        private const val ATLAS_FONT_SIZE = 32

        private const val VERT_RECT = """
            attribute vec2 aPos;
            uniform vec2 uResolution;
            varying vec2 vPos;
            void main() {
                vPos = aPos;
                vec2 ndc = vec2(aPos.x / uResolution.x * 2.0 - 1.0, 1.0 - aPos.y / uResolution.y * 2.0);
                gl_Position = vec4(ndc, 0.0, 1.0);
            }
        """

        /** Rounded-rect signed distance in px space. */
        private const val FRAG_RECT = """
            precision mediump float;
            varying vec2 vPos;
            uniform vec2 uResolution;
            uniform vec4 uRect;      // x, y, w, h
            uniform vec4 uColor;
            uniform float uRadius;
            uniform float uOpacity;
            float roundedBoxSDF(vec2 p, vec2 b, float r) {
                vec2 d = abs(p) - b + vec2(r);
                return min(max(d.x, d.y), 0.0) + length(max(d, 0.0)) - r;
            }
            void main() {
                vec2 center = uRect.xy + uRect.zw * 0.5;
                float d = roundedBoxSDF(vPos - center, uRect.zw * 0.5, uRadius);
                float alpha = 1.0 - smoothstep(-0.5, 0.5, d);
                gl_FragColor = vec4(uColor.rgb, uColor.a * alpha * uOpacity);
            }
        """

        private const val VERT_TEX = """
            attribute vec2 aPos;
            attribute vec2 aUV;
            uniform vec2 uResolution;
            varying vec2 vUV;
            void main() {
                vUV = aUV;
                vec2 ndc = vec2(aPos.x / uResolution.x * 2.0 - 1.0, 1.0 - aPos.y / uResolution.y * 2.0);
                gl_Position = vec4(ndc, 0.0, 1.0);
            }
        """

        private const val FRAG_TEX = """
            precision mediump float;
            varying vec2 vUV;
            uniform sampler2D uTex;
            uniform vec4 uColor;
            uniform float uOpacity;
            uniform float uIsAlpha;
            void main() {
                vec4 c = texture2D(uTex, vUV);
                float a = mix(c.a, c.a * c.a, uIsAlpha * 0.0) * uOpacity;
                if (uIsAlpha > 0.5) {
                    gl_FragColor = vec4(uColor.rgb, c.a * uOpacity);
                } else {
                    gl_FragColor = vec4(c.rgb, c.a * uOpacity);
                }
            }
        """
    }

    fun onSurfaceCreated() {
        programRect = buildProgram(VERT_RECT, FRAG_RECT)
        programTex = buildProgram(VERT_TEX, FRAG_TEX)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        atlasBitmap = Bitmap.createBitmap(atlasSize, atlasSize, Bitmap.Config.ARGB_8888)
        atlasCanvasObj = Canvas(atlasBitmap!!)
        atlasTextureId = createTexture()
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        viewportW = width
        viewportH = height
        GLES20.glViewport(0, 0, width, height)
    }

    fun release() {
        imageTextures.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
        imageTextures.clear()
        if (atlasTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(atlasTextureId), 0)
        atlasBitmap?.recycle()
        atlasBitmap = null
        glyphRect.clear()
    }

    fun draw(root: RenderNode) {
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(programRect)
        val resHandle = GLES20.glGetUniformLocation(programRect, "uResolution")
        GLES20.glUniform2f(resHandle, viewportW.toFloat(), viewportH.toFloat())
        drawNode(root)
        if (atlasDirty) uploadAtlas()
    }

    private fun drawNode(node: RenderNode) {
        if (node.style.display == Display.NONE || node.width <= 0f || node.height <= 0f) return
        val st = node.style
        val alpha = st.opacity.coerceIn(0f, 1f)
        if (alpha <= 0f) return

        if (st.backgroundColor != Color.TRANSPARENT) {
            drawRect(node.absX, node.absY, node.width, node.height, st.backgroundColor,
                st.borderRadius, alpha, programRect)
        }
        if (st.borderWidth > 0f && st.borderColor != Color.TRANSPARENT) {
            drawRect(node.absX, node.absY, node.width, st.borderWidth, st.borderColor, 0f, alpha, programRect)
            drawRect(node.absX, node.absY + node.height - st.borderWidth, node.width, st.borderWidth,
                st.borderColor, 0f, alpha, programRect)
            drawRect(node.absX, node.absY, st.borderWidth, node.height, st.borderColor, 0f, alpha, programRect)
            drawRect(node.absX + node.width - st.borderWidth, node.absY, st.borderWidth, node.height,
                st.borderColor, 0f, alpha, programRect)
        }

        when (node.type) {
            NodeType.TEXT -> drawTextNode(node, alpha)
            NodeType.BUTTON -> drawTextNode(node, alpha)
            NodeType.INPUT -> drawTextNode(node, alpha)
            NodeType.IMAGE -> drawImageNode(node, alpha)
            else -> Unit
        }

        for (child in node.children) drawNode(child)
    }

    private fun drawTextNode(node: RenderNode, alpha: Float) {
        val st = node.style
        val lines = node.lines
        if (lines.isEmpty()) return
        val scale = st.fontSize / ATLAS_FONT_SIZE
        val lh = if (st.lineHeight.isNaN()) st.fontSize * 1.25f else st.lineHeight
        var y = node.absY + (node.height - lh * lines.size) / 2f + (lh - st.fontSize) / 2f
        val maxLines = if (st.maxLines > 0) st.maxLines else lines.size
        for (i in 0 until minOf(lines.size, maxLines)) {
            val line = lines[i]
            val lineW = line.sumOf { (glyphFor(it)?.get(4)?.toDouble() ?: 0.0) } * scale
            var x = when (st.textAlign) {
                TextAlign.CENTER -> node.absX + (node.width - lineW.toFloat()) / 2f
                TextAlign.RIGHT -> node.absX + node.width - lineW.toFloat()
                TextAlign.LEFT -> node.absX
            }
            for (ch in line) {
                val g = glyphFor(ch) ?: continue
                val w = g[4] * scale
                val h = g[5] * scale
                drawGlyph(x, y, w, h, g[0], g[1], g[2], g[3], st.color, alpha)
                x += w
            }
            y += lh
        }
    }

    private fun drawImageNode(node: RenderNode, alpha: Float) {
        val src = node.attributes["src"] ?: return
        val tex = textureFor(src) ?: return
        drawQuadTextured(node.absX, node.absY, node.width, node.height, tex, Color.WHITE, alpha, false)
    }

    // ------------------------------------------------------------------ GL plumbing
    private fun drawRect(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float, opacity: Float, prog: Int) {
        GLES20.glUseProgram(prog)
        val pos = GLES20.glGetAttribLocation(prog, "aPos")
        val res = GLES20.glGetUniformLocation(prog, "uResolution")
        val uRect = GLES20.glGetUniformLocation(prog, "uRect")
        val uColor = GLES20.glGetUniformLocation(prog, "uColor")
        val uRadius = GLES20.glGetUniformLocation(prog, "uRadius")
        val uOpacity = GLES20.glGetUniformLocation(prog, "uOpacity")

        GLES20.glUniform2f(res, viewportW.toFloat(), viewportH.toFloat())
        GLES20.glUniform4f(uRect, x, y, w, h)
        GLES20.glUniform4f(uColor, Color.red(color) / 255f, Color.green(color) / 255f,
            Color.blue(color) / 255f, Color.alpha(color) / 255f)
        GLES20.glUniform1f(uRadius, radius)
        GLES20.glUniform1f(uOpacity, opacity)

        vertRect[0] = x; vertRect[1] = y
        vertRect[2] = x + w; vertRect[3] = y
        vertRect[4] = x; vertRect[5] = y + h
        vertRect[6] = x + w; vertRect[7] = y + h
        vertBuf.clear()
        vertBuf.put(vertRect)
        vertBuf.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, vertBuf)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private val texVerts = FloatArray(16)

    private fun drawGlyph(x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float,
                          color: Int, opacity: Float) {
        drawQuadWithUV(x, y, w, h, u0, v0, u1, v1, atlasTextureId, color, opacity, true)
    }

    private fun drawQuadTextured(x: Float, y: Float, w: Float, h: Float, tex: Int, color: Int, opacity: Float, isAlpha: Boolean) {
        drawQuadWithUV(x, y, w, h, 0f, 0f, 1f, 1f, tex, color, opacity, isAlpha)
    }

    private fun drawQuadWithUV(x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float,
                               tex: Int, color: Int, opacity: Float, isAlpha: Boolean) {
        val prog = programTex
        GLES20.glUseProgram(prog)
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aUV = GLES20.glGetAttribLocation(prog, "aUV")
        val res = GLES20.glGetUniformLocation(prog, "uResolution")
        val uTex = GLES20.glGetUniformLocation(prog, "uTex")
        val uColor = GLES20.glGetUniformLocation(prog, "uColor")
        val uOpacity = GLES20.glGetUniformLocation(prog, "uOpacity")
        val uIsAlpha = GLES20.glGetUniformLocation(prog, "uIsAlpha")

        GLES20.glUniform2f(res, viewportW.toFloat(), viewportH.toFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform4f(uColor, Color.red(color) / 255f, Color.green(color) / 255f,
            Color.blue(color) / 255f, Color.alpha(color) / 255f)
        GLES20.glUniform1f(uOpacity, opacity)
        GLES20.glUniform1f(uIsAlpha, if (isAlpha) 1f else 0f)

        // two triangles as a strip: (x,y) (x+w,y) (x,y+h) (x+w,y+h)
        texVerts[0] = x; texVerts[1] = y; texVerts[2] = u0; texVerts[3] = v0
        texVerts[4] = x + w; texVerts[5] = y; texVerts[6] = u1; texVerts[7] = v0
        texVerts[8] = x; texVerts[9] = y + h; texVerts[10] = u0; texVerts[11] = v1
        texVerts[12] = x + w; texVerts[13] = y + h; texVerts[14] = u1; texVerts[15] = v1

        val buf = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(texVerts).position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(aPos)
        val uvBuf = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        uvBuf.put(texVerts).position(2)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, uvBuf)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            error("GL link failed: $log")
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            error("GL compile failed: $log")
        }
        return s
    }

    private fun createTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun textureFor(src: String): Int? {
        imageTextures[src]?.let { return it }
        val bmp = imageProvider?.invoke(src) ?: return null
        val id = createTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        imageTextures[src] = id
        return id
    }

    // ------------------------------------------------------------------ glyph atlas
    private fun glyphFor(ch: Char): FloatArray? {
        glyphRect[ch]?.let { return it }
        val canvas = atlasCanvasObj ?: return null
        val w = atlasPaint.measureText(ch.toString()).toInt() + 2
        val fm = atlasPaint.fontMetricsInt
        val h = (fm.descent - fm.ascent) + 2
        if (atlasCursorX + w > atlasSize) {
            atlasCursorX = 0
            atlasCursorY += atlasRowHeight + 2
            atlasRowHeight = 0
        }
        if (atlasCursorY + h > atlasSize) return null   // atlas full
        canvas.drawText(ch.toString(), atlasCursorX.toFloat(), (atlasCursorY - fm.ascent).toFloat(), atlasPaint)
        val u0 = atlasCursorX / atlasSize.toFloat()
        val v0 = atlasCursorY / atlasSize.toFloat()
        val u1 = (atlasCursorX + w) / atlasSize.toFloat()
        val v1 = (atlasCursorY + h) / atlasSize.toFloat()
        val rec = floatArrayOf(u0, v0, u1, v1, w.toFloat(), h.toFloat())
        glyphRect[ch] = rec
        atlasCursorX += w + 1
        atlasRowHeight = max(atlasRowHeight, h)
        atlasDirty = true
        return rec
    }

    private fun uploadAtlas() {
        val bmp = atlasBitmap ?: return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        atlasDirty = false
    }
}
