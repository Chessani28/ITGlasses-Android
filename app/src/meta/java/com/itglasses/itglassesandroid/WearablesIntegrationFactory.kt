package com.itglasses.itglassesandroid

import com.itglasses.itglassesandroid.databinding.ActivityMainBinding

internal object WearablesIntegrationFactory {
    fun create(
        activity: MainActivity,
        binding: ActivityMainBinding,
    ): WearablesIntegration = MetaWearablesIntegration(activity, binding)
}
