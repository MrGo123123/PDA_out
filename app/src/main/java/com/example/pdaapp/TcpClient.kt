package com.example.pdaapp

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * TCP 客户端类，负责与服务器（client.py）建立长连接，接收和发送 JSON 行消息。
 * 增加读超时处理和心跳发送机制，保持连接稳定。
 */
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

    // 读超时时间（毫秒），10秒无数据则认为超时
    private val readTimeoutMs = 10000

    /**
     * 启动 TCP 连接并进入读取循环，在协程中运行。
     * 当读取超时时，会发送心跳包保持连接，而不是断开重连。
     */
    fun start() {
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    // 建立连接
                    socket = Socket(host, port)
                    socket!!.soTimeout = readTimeoutMs  // 设置读超时
                    writer = OutputStreamWriter(socket!!.getOutputStream(), "UTF-8")
                    reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), "UTF-8"))

                    // 通知连接成功
                    withContext(Dispatchers.Main) { listener.onConnected() }

                    // 发送身份
                    sendMsg(JSONObject().apply {
                        put("type", "identity")
                        put("role", "pda_app")
                    })

                    // 读取循环
                    var line: String?
                    while (isActive) {
                        try {
                            line = reader?.readLine()
                            if (line == null) break  // 服务端关闭连接

                            if (line.isNotEmpty()) {
                                try {
                                    val msg = JSONObject(line)
                                    withContext(Dispatchers.Main) {
                                        listener.onMessageReceived(msg)
                                    }
                                } catch (e: Exception) {
                                    Log.e("TcpClient", "JSON解析失败: $line", e)
                                }
                            }
                        } catch (e: SocketTimeoutException) {
                            // 读超时，发送心跳包保持连接
                            sendMsg(JSONObject().apply { put("type", "heartbeat") })
                        } catch (e: Exception) {
                            Log.e("TcpClient", "读取异常", e)
                            break
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

    /**
     * 发送 JSON 消息，自动追加换行符。
     */
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

    /**
     * 停止客户端，取消协程并关闭连接。
     */
    fun stop() {
        job?.cancel()
        close()
    }

    /**
     * 关闭 socket、reader、writer。
     */
    private fun close() {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null; reader = null; socket = null
    }
}
