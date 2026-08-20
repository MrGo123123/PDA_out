package com.example.pdaapp

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.SocketTimeoutException

class TcpClient(
    private val host: String,
    private val port: Int,
    private val listener: TcpListener
) {
    interface TcpListener {
        fun onConnected()
        fun onDisconnected()
        fun onMessageReceived(msg: JSONObject)
    }

    private var socket: Socket? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private var job: Job? = null

    private val readTimeoutMs = 10000  // 读超时10秒

    fun start() {
        job = CoroutineScope(Dispatchers.IO).launch {
            // 外层循环：确保任何异常都不会导致协程退出，持续重连
            while (true) {
                try {
                    connectAndRead()
                } catch (e: Exception) {
                    Log.e("TcpClient", "连接或读取异常，3秒后重连: ${e.message}")
                } finally {
                    close()
                    withContext(Dispatchers.Main) { listener.onDisconnected() }
                    delay(3000)
                }
            }
        }
    }

    /**
     * 建立连接并进入读取循环，任何异常抛出后由外层捕获。
     */
    private suspend fun connectAndRead() {
        // 建立连接
        socket = Socket(host, port)
        socket!!.soTimeout = readTimeoutMs
        writer = OutputStreamWriter(socket!!.getOutputStream(), "UTF-8")
        reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), "UTF-8"))

        Log.d("TcpClient", "连接成功: $host:$port")
        withContext(Dispatchers.Main) { listener.onConnected() }

        // 发送身份
        sendMsg(JSONObject().apply {
            put("type", "identity")
            put("role", "pda_app")
        })

        // 读取循环
        var line: String?
        while (true) {
            try {
                line = reader?.readLine()
                if (line == null) {
                    Log.d("TcpClient", "服务端关闭连接")
                    break
                }
                if (line.isNotEmpty()) {
                    val msg = JSONObject(line)
                    Log.d("TcpClient", "收到消息: ${msg.optString("type")}")
                    withContext(Dispatchers.Main) { listener.onMessageReceived(msg) }
                }
            } catch (e: SocketTimeoutException) {
                // 超时，发送心跳保持连接
                sendMsg(JSONObject().apply { put("type", "heartbeat") })
            } catch (e: Exception) {
                Log.e("TcpClient", "读取异常: ${e.message}")
                break
            }
        }
    }

    fun sendMsg(msg: JSONObject) {
        try {
            writer?.let {
                it.write(msg.toString())
                it.write("\n")
                it.flush()
                Log.d("TcpClient", "发送消息: ${msg.optString("type")}")
            }
        } catch (e: Exception) {
            Log.e("TcpClient", "发送失败: ${e.message}")
        }
    }

    fun stop() {
        job?.cancel()
        close()
    }

    private fun close() {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null; reader = null; socket = null
    }
}
