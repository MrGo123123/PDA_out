package com.example.pdaapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), PdaService.Callback {
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvScanContent: TextView
    private lateinit var tvDetailHint: TextView
    private lateinit var tvDetailContent: TextView
    private lateinit var tvCounter: TextView
    private lateinit var tvEffectiveOut: TextView
    private lateinit var btnSettings: Button

    private var pdaService: PdaService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PdaService.LocalBinder
            pdaService = binder.getService()
            pdaService?.setCallback(this@MainActivity)
            serviceBound = true
            updateUI()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            pdaService?.setCallback(null)
            pdaService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvConnectionStatus = findViewById(R.id.tv_connection_status)
        tvScanContent = findViewById(R.id.tv_scan_content)
        tvDetailHint = findViewById(R.id.tv_detail_hint)
        tvDetailContent = findViewById(R.id.tv_detail_content)
        tvCounter = findViewById(R.id.tv_counter)
        tvEffectiveOut = findViewById(R.id.tv_effective_out)
        btnSettings = findViewById(R.id.btn_settings)

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 绑定服务
        val intent = Intent(this, PdaService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    // Callback 实现
    override fun onConnectionStateChanged(connected: Boolean) {
        runOnUiThread {
            tvConnectionStatus.text = if (connected) "已连接" else "未连接"
            tvConnectionStatus.setTextColor(if (connected) 0xFF27AE60.toInt() else 0xFFE74C3C.toInt())
        }
    }

    override fun onDataUpdated() {
        runOnUiThread { updateUI() }
    }

    override fun onDialogRequired(dialogType: String, code: String, headers: List<String>?, row: List<String>?) {
        runOnUiThread {
            showPdaDialog(dialogType, code, headers, row)
        }
    }

    private fun updateUI() {
        pdaService?.let { svc ->
            tvScanContent.text = if (svc.lastCode.isEmpty()) "等待扫码..." else svc.lastCode
            tvDetailHint.text = svc.detailHint
            tvDetailContent.text = svc.detailContent
            tvCounter.text = "计数: ${svc.printCounter} / ${svc.printInterval}"
            tvEffectiveOut.text = "有效出库: ${svc.effectiveOut}"
            tvConnectionStatus.text = if (svc.isServerConnected()) "已连接" else "未连接"
            tvConnectionStatus.setTextColor(if (svc.isServerConnected()) 0xFF27AE60.toInt() else 0xFFE74C3C.toInt())
        }
    }

    private fun showPdaDialog(dialogType: String, code: String, headers: List<String>?, row: List<String>?) {
        when (dialogType) {
            "no_data" -> {
                AlertDialog.Builder(this)
                    .setTitle("无探伤数据")
                    .setMessage("当前码【${code}】已入库，但未查询到探伤数据，请确认")
                    .setPositiveButton("确认出库") { _, _ -> pdaService?.sendDialogResponse(code, "out") }
                    .setNegativeButton("取消出库") { _, _ -> pdaService?.sendDialogResponse(code, "cancel") }
                    .setCancelable(false)
                    .show()
                    .also { pdaService?.setCurrentDialog(it) }
            }
            "ng" -> {
                val detailStr = buildString {
                    if (headers != null && row != null) {
                        for (i in headers.indices) {
                            if (i >= row.size) break
                            append("【${headers[i]}】: ${row[i]}\n")
                        }
                    }
                }
                AlertDialog.Builder(this)
                    .setTitle("探伤结果 NG - 请选择操作")
                    .setMessage("条码【${code}】探伤结果为 NG，详细信息：\n${detailStr}")
                    .setPositiveButton("确认出库") { _, _ ->
                        AlertDialog.Builder(this)
                            .setTitle("二次确认")
                            .setMessage("确定强制出库该NG件？")
                            .setPositiveButton("确定") { _, _ -> pdaService?.sendDialogResponse(code, "out") }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    .setNeutralButton("删除数据") { _, _ ->
                        AlertDialog.Builder(this)
                            .setTitle("二次确认")
                            .setMessage("确定永久删除该条记录？")
                            .setPositiveButton("确定") { _, _ -> pdaService?.sendDialogResponse(code, "delete") }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    .setNegativeButton("取消出库") { _, _ -> pdaService?.sendDialogResponse(code, "cancel") }
                    .setCancelable(false)
                    .show()
                    .also { pdaService?.setCurrentDialog(it) }
            }
        }
    }
}
