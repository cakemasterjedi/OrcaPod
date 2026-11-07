package org.orcapod.app.ui.viewport

import android.opengl.GLES30.*
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal object Gl {
    private const val TAG = "OrcaPodGl"

    fun program(vertex: String, fragment: String): Int {
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, vertex))
        glAttachShader(p, shader(GL_FRAGMENT_SHADER, fragment))
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("program link failed: ${glGetProgramInfoLog(p)}")
        return p
    }

    private fun shader(type: Int, source: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, source)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, glGetShaderInfoLog(s))
            error("shader compile failed: ${glGetShaderInfoLog(s)}")
        }
        return s
    }

    fun floatBuffer(data: FloatArray, count: Int = data.size): FloatBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data, 0, count)
            position(0)
        }

    /** Uploads interleaved float vertices into a new VBO; returns its name. */
    fun vbo(data: FloatArray, count: Int = data.size): Int {
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        glBindBuffer(GL_ARRAY_BUFFER, ids[0])
        glBufferData(GL_ARRAY_BUFFER, count * 4, floatBuffer(data, count), GL_STATIC_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        return ids[0]
    }

    fun deleteVbo(id: Int) {
        if (id != 0) glDeleteBuffers(1, intArrayOf(id), 0)
    }
}

/** Shaded triangles: position + normal, Lambert lighting with a headlight. */
internal class MeshProgram {
    val id = Gl.program(
        """#version 300 es
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        uniform mat4 uMvp;
        uniform mat3 uNormalMat;
        out vec3 vNormal;
        void main() {
            vNormal = uNormalMat * aNormal;
            gl_Position = uMvp * vec4(aPos, 1.0);
        }""",
        """#version 300 es
        precision mediump float;
        in vec3 vNormal;
        uniform vec4 uColor;
        uniform vec3 uLight;
        out vec4 fragColor;
        void main() {
            vec3 n = normalize(vNormal);
            float d = abs(dot(n, uLight));
            float sky = 0.5 + 0.5 * n.z;
            vec3 c = uColor.rgb * (0.30 + 0.55 * d + 0.15 * sky);
            fragColor = vec4(c, uColor.a);
        }"""
    )
    val mvp = glGetUniformLocation(id, "uMvp")
    val normalMat = glGetUniformLocation(id, "uNormalMat")
    val color = glGetUniformLocation(id, "uColor")
    val light = glGetUniformLocation(id, "uLight")
}

/** Unlit geometry with a per-vertex colour: position + rgb. */
internal class ColorProgram {
    val id = Gl.program(
        """#version 300 es
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aColor;
        uniform mat4 uMvp;
        out vec3 vColor;
        void main() {
            vColor = aColor;
            gl_Position = uMvp * vec4(aPos, 1.0);
        }""",
        """#version 300 es
        precision mediump float;
        in vec3 vColor;
        uniform float uAlpha;
        out vec4 fragColor;
        void main() { fragColor = vec4(vColor, uAlpha); }"""
    )
    val mvp = glGetUniformLocation(id, "uMvp")
    val alpha = glGetUniformLocation(id, "uAlpha")
}

/** Binds a VBO of interleaved [xyz, 3 more floats] vertices to attributes 0 and 1. */
internal fun bindInterleaved(vbo: Int) {
    glBindBuffer(GL_ARRAY_BUFFER, vbo)
    glEnableVertexAttribArray(0)
    glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0)
    glEnableVertexAttribArray(1)
    glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12)
}
