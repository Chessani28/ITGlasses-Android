package com.itglasses.itglassesandroid

import android.app.Application
import android.os.Build
import android.util.Log
import com.meta.wearable.dat.core.Wearables

class ITGlassesApplication : Application() {

    var metaSdkReady: Boolean = false
        private set

    var metaInitializationError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val result = Wearables.initialize(applicationContext)
        metaSdkReady = result.isSuccess
        if (result.isFailure) {
            metaInitializationError = result.errorOrNull()?.description
                ?: "The Meta Wearables SDK could not be initialized."
            Log.e(TAG, "Meta Wearables SDK initialization failed: $metaInitializationError")
        }
    }

    private companion object {
        const val TAG = "ITGlasses"
    }
}
