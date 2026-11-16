package com.itglasses.itglassesandroid

import android.content.Intent

internal interface WearablesIntegration {
    fun onCreate(intent: Intent)
    fun onNewIntent(intent: Intent)
    fun register()
    fun startSession()
    fun stopSession()
    fun onDestroy()
}
