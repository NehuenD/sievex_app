package com.sievex.probe

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Probe OK — sideloading works"
            textSize = 22f
            setPadding(48, 200, 48, 48)
        })
    }
}
