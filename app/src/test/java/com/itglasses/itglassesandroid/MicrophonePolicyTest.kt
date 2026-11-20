package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicrophonePolicyTest {
    // Tipos de AudioDeviceInfo: 1 = auricular del teléfono, 2 = bocina, 3 = audífonos con cable.
    @Test fun picksBluetoothScoAmongOtherDevices() {
        assertEquals(2, MicrophonePolicy.pickBluetooth(listOf(1, 2, MicrophonePolicy.TYPE_BLUETOOTH_SCO)))
    }

    @Test fun picksBleHeadset() {
        assertEquals(0, MicrophonePolicy.pickBluetooth(listOf(MicrophonePolicy.TYPE_BLE_HEADSET, 2)))
    }

    @Test fun noBluetoothMeansPhoneMicrophone() {
        assertNull(MicrophonePolicy.pickBluetooth(listOf(1, 2, 3)))
        assertNull(MicrophonePolicy.pickBluetooth(emptyList()))
    }

    @Test fun labelUsesDeviceName() {
        assertEquals("micrófono de Ray-Ban Meta", MicrophonePolicy.label(" Ray-Ban Meta "))
        assertEquals("micrófono Bluetooth", MicrophonePolicy.label(""))
        assertEquals("micrófono Bluetooth", MicrophonePolicy.label(null))
    }
}
