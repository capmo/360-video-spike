package de.capmo.insta360spike

import android.app.Application
import com.arashivision.sdkmedia.InstaMediaSDK

class SpikeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Insta360 rendering SDK. Camera SDK (InstaCameraSDK.init) is intentionally not
        // pulled in — this spike is playback-only, no Wi-Fi/USB camera pairing.
        InstaMediaSDK.init(this)
    }
}
