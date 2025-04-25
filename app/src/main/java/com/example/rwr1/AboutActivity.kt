package com.example.rwr1

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about) // 确保你的布局文件名为 activity_about.xml
        // 在这里你可以添加任何需要在 AboutActivity 启动时执行的代码
    }
}