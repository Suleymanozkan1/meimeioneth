package com.lexiflow

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.lexiflow.ui.LexiFlowRoot
import com.lexiflow.ui.theme.LexiFlowTheme

class MainActivity : ComponentActivity() {
    /** Deep link (lexiflow://...) waiting to be handled by the navigation graph. */
    private val pendingLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh start: rotation / process restore must not replay an already handled link.
        if (savedInstanceState == null) pendingLink.value = intent?.data?.toString()
        val container = (application as LexiFlowApp).container
        setContent {
            LexiFlowTheme {
                LexiFlowRoot(container, pendingLink.value) { pendingLink.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingLink.value = intent.data?.toString()
    }
}
