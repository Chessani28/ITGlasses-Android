package com.itglasses.itglassesandroid

import android.content.Intent
import com.itglasses.itglassesandroid.databinding.ActivityMainBinding

internal object WearablesIntegrationFactory {
    fun create(
        @Suppress("UNUSED_PARAMETER") activity: MainActivity,
        binding: ActivityMainBinding,
    ): WearablesIntegration = PhoneWearablesIntegration(binding)
}

private class PhoneWearablesIntegration(
    private val binding: ActivityMainBinding,
) : WearablesIntegration {

    override fun onCreate(intent: Intent) {
        binding.tvMetaStatus.text =
            "Meta DAT is not included in the phone-only build. Phone audio remains available."
        binding.btnMetaRegister.isEnabled = false
        binding.btnMetaSession.isEnabled = false
        binding.btnMetaStop.isEnabled = false
    }

    override fun onNewIntent(intent: Intent) = Unit
    override fun register() = Unit
    override fun startSession() = Unit
    override fun stopSession() = Unit
    override fun onDestroy() = Unit
}
