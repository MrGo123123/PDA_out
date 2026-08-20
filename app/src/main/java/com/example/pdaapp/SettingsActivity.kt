package com.example.pdaapp

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etServerIp = findViewById<EditText>(R.id.et_server_ip)
        val etServerPort = findViewById<EditText>(R.id.et_server_port)
        val btnSave = findViewById<Button>(R.id.btn_save)
        val btnBack = findViewById<Button>(R.id.btn_back)
        val tvCounter = findViewById<TextView>(R.id.tv_counter_display)
        val tvEffectiveOut = findViewById<TextView>(R.id.tv_eff_out)
        val tvInterval = findViewById<TextView>(R.id.tv_interval)
        val btnResetCounter = findViewById<Button>(R.id.btn_reset_counter)
        val btnSetInterval = findViewById<Button>(R.id.btn_set_interval)

        val prefs = getSharedPreferences("pda_settings", MODE_PRIVATE)
        etServerIp.setText(prefs.getString("server_ip", "192.168.1.36"))
        etServerPort.setText(prefs.getInt("server_port", 12347).toString())

        btnSave.setOnClickListener {
            val ip = etServerIp.text.toString()
            val port = etServerPort.text.toString().toIntOrNull() ?: 12347
            prefs.edit().putString("server_ip", ip).putInt("server_port", port).apply()
            Toast.makeText(this, "配置已保存，需重启服务生效", Toast.LENGTH_SHORT).show()
        }

        btnBack.setOnClickListener { finish() }

        // 从 PdaService 获取数据（显式类型转换）
        val service = (application as App).pdaService as? PdaService
        service?.let {
            tvCounter.text = "计数: ${it.printCounter} / ${it.printInterval}"
            tvEffectiveOut.text = "有效出库: ${it.effectiveOut}"
            tvInterval.text = "设定间隔: ${it.printInterval}"
        }

        btnResetCounter.setOnClickListener {
            // 显式类型转换后调用方法
            ((application as App).pdaService as? PdaService)?.sendResetCounter()
        }

        btnSetInterval.setOnClickListener {
            val input = EditText(this)
            AlertDialog.Builder(this)
                .setTitle("设定打印间隔")
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val interval = input.text.toString().toIntOrNull()
                    if (interval != null) {
                        // 显式类型转换后调用方法
                        ((application as App).pdaService as? PdaService)?.sendSetInterval(interval)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
}
    }
}
