package com.rdp.client

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * Minimal launcher activity establishing the UI entry point.
 * In Milestone 2, this expands into the full AVNC-style HomeActivity hosting the
 * server bookmark list, quick connect modal, and settings drawer.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        if (toolbar != null) {
            setSupportActionBar(toolbar)
            supportActionBar?.title = getString(R.string.app_name)
        }

        val tvStatus = findViewById<TextView>(R.id.tvStatusMessage)
        tvStatus?.text = getString(R.string.status_empty_profiles)
    }
}
