package com.example.pdaapp

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Binder
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.lang.ref.WeakReference

class PdaService : Service(), TcpClient.TcpListener {
    companion object {
        const val ACTION_SCAN = "android.intent.action.DECODE_DATA"
        const val EXTRA_BARCODE_STRING = "barcode_string"
        const val EXTRA_BARCODE = "barcode"
    }

    private val binder = LocalBinder()
    private var tcpClient: TcpClient? = null
    private var isConnected = false

    var printCounter = 0
    var printInterval = 10
    var effectiveOut = "0"
    var lastCode = ""
    var detailHint = "等待扫码..."
    var detailContent = ""
    var isAuto = true
    var packPlcMode = true
    var packMode = true

    private var currentDialog: androidx.appcompat.app.AlertDialog? = null

    private var vibrator: Vibrator? = null
    private var alertRingtone: Ringtone? = null
    private var isAlerting = false

    private var preDialogHint = "等待扫码..."

    interface Callback {
        fun onConnectionStateChanged(connected: Boolean)
        fun onDataUpdated()
        fun onDialogRequired(dialogType: String, code: String, headers: List<String>?, row: List<String>?, title: String?, message: String?)
        fun onDialogDismissed()
    }

    private var callbackRef: WeakReference<Callback>? = null

    inner class LocalBinder : Binder() {
        fun getService(): PdaService = this@PdaService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setCallback(cb: Callback?) {
        callbackRef = cb?.let { WeakReference(it) }
    }

    private fun getCallback(): Callback? = callbackRef?.get()

    override fun onCreate() {
        super.onCreate()
        (application as App).pdaService = this
        startForeground(1, createNotification())
        registerScanReceiver()
        connectToServer()
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
    }

    override fun onDestroy() {
        stopAlert()
        unregisterScanReceiver()
        tcpClient?.stop()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("PDA扫描服务")
            .setContentText("正在运行...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun registerScanReceiver() {
        val filter = IntentFilter(ACTION_SCAN)
        registerReceiver(scanReceiver, filter)
    }

    private fun unregisterScanReceiver() {
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SCAN) {
                val barcode = intent.getStringExtra(EXTRA_BARCODE_STRING)
                    ?: intent.getByteArrayExtra(EXTRA_BARCODE)?.let { String(it) }
                if (!barcode.isNullOrEmpty()) {
                    sendScanData(barcode.trim())
                }
            }
        }
    }

    private fun sendScanData(code: String) {
        val msg = JSONObject().apply {
            put("type", "scan_data")
            put("code", code)
            put("ts", System.currentTimeMillis() / 1000.0)
        }
        tcpClient?.sendMsg(msg)
    }

    private fun connectToServer() {
        val prefs = getSharedPreferences("pda_settings", MODE_PRIVATE)
        val serverIp = prefs.getString("server_ip", "192.168.1.36") ?: "192.168.1.36"
        val serverPort = prefs.getInt("server_port", 12347)
        Log.d("PdaService", "连接服务器: $serverIp:$serverPort")
        tcpClient = TcpClient(serverIp, serverPort, this)
        tcpClient?.start()
    }

    // 发送清零计数器命令
    fun sendResetCounter() {
        val msg = JSONObject().apply { put("type", "reset_counter") }
        tcpClient?.sendMsg(msg)
    }

    // 发送设定打印间隔命令
    fun sendSetInterval(interval: Int) {
        val msg = JSONObject().apply {
            put("type", "set_interval")
            put("value", interval)
        }
        tcpClient?.sendMsg(msg)
    }

    // 发送弹窗操作响应
    fun sendDialogResponse(code: String, action: String) {
        val msg = JSONObject().apply {
            put("type", "pda_dialog_response")
            put("code", code)
            put("action", action)
        }
        tcpClient?.sendMsg(msg)
        dismissCurrentDialog()
    }

    fun setCurrentDialog(dialog: androidx.appcompat.app.AlertDialog) {
        currentDialog = dialog
    }

    fun dismissCurrentDialog() {
        currentDialog?.dismiss()
        currentDialog = null
        stopAlert()
        detailHint = preDialogHint
        getCallback()?.onDialogDismissed()
        getCallback()?.onDataUpdated()
    }

    fun isServerConnected(): Boolean = isConnected

    override fun onConnected() {
        Log.d("PdaService", "连接成功")
        isConnected = true
        getCallback()?.onConnectionStateChanged(true)
    }

    override fun onDisconnected() {
        Log.d("PdaService", "连接断开")
        isConnected = false
        dismissCurrentDialog()
        getCallback()?.onConnectionStateChanged(false)
    }

    override fun onMessageReceived(msg: JSONObject) {
        val type = msg.optString("type")
        Log.d("PdaService", "收到消息类型: $type")
        when (type) {
            "status_update" -> {
                printCounter = msg.optInt("print_counter", printCounter)
                printInterval = msg.optInt("print_interval", printInterval)
                effectiveOut = msg.optString("effective_out", effectiveOut)
                lastCode = msg.optString("last_code", lastCode)
                detailHint = msg.optString("detail_hint", detailHint)
                detailContent = msg.optString("detail_content", detailContent)
                isAuto = msg.optBoolean("is_auto", true)
                packPlcMode = msg.optBoolean("pack_plc_mode", true)
                packMode = msg.optBoolean("pack_mode", true)
                getCallback()?.onDataUpdated()
            }
            "scan_detail" -> {
                val code = msg.optString("code")
                val exist = msg.optBoolean("exist")
                if (!exist) {
                    detailContent = "条码：$code\n无有效记录"
                } else {
                    val headers = msg.optJSONArray("headers")
                    val row = msg.optJSONArray("row")
                    val sb = StringBuilder()
                    if (headers != null && row != null) {
                        for (i in 0 until headers.length()) {
                            if (i >= row.length()) break
                            sb.append("【${headers.optString(i)}】: ${row.optString(i)}\n")
                        }
                    }
                    detailContent = sb.toString()
                }
                getCallback()?.onDataUpdated()
            }
            "show_dialog" -> {
                val dialogType = msg.optString("dialog_type")
                val code = msg.optString("code")
                val title = msg.optString("title", "")
                val message = msg.optString("message", "")
                val headers = msg.optJSONArray("headers")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }
                }
                val row = msg.optJSONArray("row")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }
                }
                preDialogHint = detailHint
                detailHint = "等待弹窗操作"
                getCallback()?.onDataUpdated()
                startAlert()
                getCallback()?.onDialogRequired(dialogType, code, headers, row, title, message)
            }
            "popup_state" -> {
                val alarm = msg.optBoolean("alarm")
                if (!alarm) {
                    dismissCurrentDialog()
                }
            }
            "heartbeat", "heartbeat_ack" -> {
                // 忽略心跳消息
            }
        }
    }

    private fun startAlert() {
        if (isAlerting) return
        isAlerting = true

        // 循环震动
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 500, 500), 0)
            }
        } catch (e: Exception) {
            Log.e("PdaService", "震动启动失败: ${e.message}")
        }

        // 循环响铃
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            alertRingtone = RingtoneManager.getRingtone(applicationContext, uri)
            alertRingtone?.isLooping = true
            alertRingtone?.play()
        } catch (e: Exception) {
            Log.e("PdaService", "声音启动失败: ${e.message}")
        }
    }

    private fun stopAlert() {
        if (!isAlerting) return
        isAlerting = false

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e("PdaService", "震动停止失败: ${e.message}")
        }

        try {
            alertRingtone?.stop()
            alertRingtone = null
        } catch (e: Exception) {
            Log.e("PdaService", "声音停止失败: ${e.message}")
        }
    }
}
