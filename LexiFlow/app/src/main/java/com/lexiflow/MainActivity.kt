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
        // Exported activity: keep only action + data from the launching intent. Otherwise another app could pass
        // NavController deep-link extras (android-support-nav:controller:*) and open any screen, skipping our router.
        dropExtras(intent)
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
        dropExtras(intent)
        super.onNewIntent(intent)
        setIntent(intent)
        pendingLink.value = intent.data?.toString()
    }

    /** The app never reads launch extras; clearing them also removes NavController's android-support-nav:* keys. */
    private fun dropExtras(source: Intent?) {
        source?.replaceExtras(null as Bundle?)
    }
}
