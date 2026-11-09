package com.brother.printscan.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants as AndroidUsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UsbConnectionState(
    val isConnected: Boolean = false,
    val deviceName: String = "No Device Connected",
    val hasPermission: Boolean = false,
    val canScan: Boolean = false,
    val canPrint: Boolean = false,
    val errorMessage: String? = null
)

class BrotherUsbManager(private val context: Context) {

    companion object {
        private const val TAG = "BrotherUsbManager"
        const val ACTION_USB_PERMISSION = "com.brother.printscan.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _connectionState = MutableStateFlow(UsbConnectionState())
    val connectionState: StateFlow<UsbConnectionState> = _connectionState.asStateFlow()

    private var activeDevice: UsbDevice? = null
    var activeConnection: UsbDeviceConnection? = null
        private set

    var scanEndpointIn: UsbEndpoint? = null
        private set
    var scanEndpointOut: UsbEndpoint? = null
        private set
    var printEndpointOut: UsbEndpoint? = null
        private set

    private var claimedInterface: UsbInterface? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let { initializeDevice(it) }
                        } else {
                            _connectionState.value = _connectionState.value.copy(
                                errorMessage = "USB Permission denied by user"
                            )
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    checkConnectedDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    disconnect()
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        checkConnectedDevices()
    }

    fun unregister() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver not registered: ${e.message}")
        }
        disconnect()
    }

    fun checkConnectedDevices() {
        val deviceList = usbManager.deviceList
        Log.d(TAG, "Scanning for USB devices... Found ${deviceList.size} devices")

        var brotherDevice: UsbDevice? = null
        for (device in deviceList.values) {
            if (device.vendorId == UsbConstants.BROTHER_VENDOR_ID) {
                brotherDevice = device
                break
            }
        }

        if (brotherDevice != null) {
            activeDevice = brotherDevice
            if (usbManager.hasPermission(brotherDevice)) {
                initializeDevice(brotherDevice)
            } else {
                requestPermission(brotherDevice)
            }
        } else {
            _connectionState.value = UsbConnectionState(
                isConnected = false,
                deviceName = "No Brother Device Found"
            )
        }
    }

    private fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun initializeDevice(device: UsbDevice) {
        try {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                _connectionState.value = UsbConnectionState(
                    errorMessage = "Cannot open connection to Brother USB device"
                )
                return
            }

            activeConnection = connection
            scanEndpointIn = null
            scanEndpointOut = null
            printEndpointOut = null

            // Enumerate interfaces and endpoints
            for (i in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(i)
                connection.claimInterface(usbInterface, true)
                claimedInterface = usbInterface

                for (j in 0 until usbInterface.endpointCount) {
                    val ep = usbInterface.getEndpoint(j)
                    if (ep.type == AndroidUsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == AndroidUsbConstants.USB_DIR_IN) {
                            if (scanEndpointIn == null) scanEndpointIn = ep
                        } else {
                            if (scanEndpointOut == null) scanEndpointOut = ep
                            if (printEndpointOut == null) printEndpointOut = ep
                        }
                    }
                }
            }

            val canScan = scanEndpointIn != null && scanEndpointOut != null
            val canPrint = printEndpointOut != null
            val modelName = device.productName ?: "Brother DCP Series (${String.format("0x%04X", device.productId)})"

            _connectionState.value = UsbConnectionState(
                isConnected = true,
                deviceName = modelName,
                hasPermission = true,
                canScan = canScan,
                canPrint = canPrint,
                errorMessage = null
            )
            Log.d(TAG, "Device initialized: $modelName, canScan: $canScan, canPrint: $canPrint")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize device", e)
            _connectionState.value = UsbConnectionState(
                errorMessage = "Init error: ${e.message}"
            )
        }
    }

    fun disconnect() {
        try {
            claimedInterface?.let {
                activeConnection?.releaseInterface(it)
            }
            activeConnection?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing connection: ${e.message}")
        } finally {
            activeConnection = null
            claimedInterface = null
            scanEndpointIn = null
            scanEndpointOut = null
            printEndpointOut = null
            activeDevice = null
            _connectionState.value = UsbConnectionState()
        }
    }
}
