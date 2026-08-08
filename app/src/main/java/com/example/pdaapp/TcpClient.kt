package com.example.pdaapp

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

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

    fun start() {
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    socket = Socket(host, port)
                    writer = OutputStreamWriter(socket!!.getOutputStream(), "UTF-8")
                    reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), "UTF-8"))
                    withContext(Dispatchers.Main) { listener.onConnected() }
                    // 发送身份
                    sendMsg(JSONObject().apply {
                        put("type", "identity")
                        put("role", "pda_app")
                    })
                    // 读取循环
                    var line: String?
                    while (isActive) {
                        line = reader?.readLine() ?: break
                        if (line != null && line.isNotEmpty()) {
                            try {
                                val msg = JSONObject(line)
                                withContext(Dispatchers.Main) { listener.onMessageReceived(msg) }
                            } catch (e: Exception) {
                                Log.e("TcpClient", "JSON解析失败: $line", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TcpClient", "连接或读取异常", e)
                } finally {
                    close()
                    withContext(Dispatchers.Main) { listener.onDisconnected() }
                    delay(3000) // 重连延迟
                }
            }
        }
    }

    fun sendMsg(msg: JSONObject) {
        try {
            writer?.let {
                it.write(msg.toString())
                it.write("\n")
                it.flush()
            }
        } catch (e: Exception) {
            Log.e("TcpClient", "发送失败", e)
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
