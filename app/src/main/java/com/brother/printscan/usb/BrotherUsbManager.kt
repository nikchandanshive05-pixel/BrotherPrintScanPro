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
    val endpointDetails: String = "",
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

    var scanInterface: UsbInterface? = null
        private set
    var printInterface: UsbInterface? = null
        private set

    var scanEndpointIn: UsbEndpoint? = null
        private set
    var scanEndpointOut: UsbEndpoint? = null
        private set
    var printEndpointOut: UsbEndpoint? = null
        private set

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
                            Log.d(TAG, "USB Permission granted for: ${device?.deviceName}")
                            device?.let { initializeDevice(it) }
                        } else {
                            _connectionState.value = _connectionState.value.copy(
                                errorMessage = "USB Permission denied by user"
                            )
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(TAG, "USB Device Attached")
                    checkConnectedDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.d(TAG, "USB Device Detached")
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
                    errorMessage = "Cannot open connection to Brother USB device. Please re-plug OTG cable."
                )
                return
            }

            activeConnection = connection
            scanEndpointIn = null
            scanEndpointOut = null
            printEndpointOut = null
            scanInterface = null
            printInterface = null

            val epSummary = StringBuilder()

            // Forensic discovery from libsane-brother4.so.1.0.7 / ext_20.ini:
            // DCP-B7500D (PID 0x041b):
            // Interface 0: Printer (0x01 Bulk OUT, 0x82 Bulk IN)
            // Interface 1: Scanner (0x03 Bulk OUT, 0x84 Bulk IN)
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                epSummary.append("IF $i (Class ${iface.interfaceClass}): ")

                if (iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER || i == 0) {
                    printInterface = iface
                }
                if (iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPECIFIC || i == 1) {
                    scanInterface = iface
                }

                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    val isBulk = ep.type == AndroidUsbConstants.USB_ENDPOINT_XFER_BULK
                    val isIn = ep.direction == AndroidUsbConstants.USB_DIR_IN

                    epSummary.append("0x${ep.address.toString(16)} ")

                    if (isBulk) {
                        if (i == 1 || iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPECIFIC) {
                            // Scanner interface endpoints
                            if (isIn) scanEndpointIn = ep else scanEndpointOut = ep
                        } else {
                            // Printer interface endpoints
                            if (!isIn) printEndpointOut = ep
                        }
                    }
                }
                epSummary.append(" | ")
            }

            val canScan = scanEndpointIn != null && scanEndpointOut != null
            val canPrint = printEndpointOut != null
            val modelName = device.productName ?: "Brother DCP-B7500D (0x${device.productId.toString(16)})"

            _connectionState.value = UsbConnectionState(
                isConnected = true,
                deviceName = modelName,
                hasPermission = true,
                canScan = canScan,
                canPrint = canPrint,
                endpointDetails = epSummary.toString(),
                errorMessage = null
            )
            Log.d(TAG, "Device ready: $modelName. Summary: $epSummary")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize device", e)
            _connectionState.value = UsbConnectionState(
                errorMessage = "Init error: ${e.message}"
            )
        }
    }

    /**
     * Standard USB CLEAR_FEATURE ENDPOINT_HALT request matching libusb_clear_halt().
     */
    fun clearHalt(endpointAddress: Int): Boolean {
        val conn = activeConnection ?: return false
        return try {
            val rc = conn.controlTransfer(
                0x02, // USB_RECIP_ENDPOINT | USB_TYPE_STANDARD | USB_DIR_OUT
                0x01, // USB_REQ_CLEAR_FEATURE
                0x00, // ENDPOINT_HALT
                endpointAddress,
                null,
                0,
                1000
            )
            Log.d(TAG, "clearHalt(0x${endpointAddress.toString(16)}): rc=$rc")
            rc >= 0
        } catch (e: Exception) {
            Log.w(TAG, "clearHalt error: ${e.message}")
            false
        }
    }

    /**
     * Claims the scanner interface exclusively for scanning operations.
     */
    fun claimScannerInterface(): Boolean {
        val conn = activeConnection ?: return false
        val iface = scanInterface ?: return false
        // Release printer interface if claimed to prevent kernel conflicts
        printInterface?.let { conn.releaseInterface(it) }
        return conn.claimInterface(iface, true)
    }

    /**
     * Releases scanner interface.
     */
    fun releaseScannerInterface() {
        val conn = activeConnection ?: return
        scanInterface?.let { conn.releaseInterface(it) }
    }

    /**
     * Claims printer interface for printing.
     */
    fun claimPrinterInterface(): Boolean {
        val conn = activeConnection ?: return false
        val iface = printInterface ?: return false
        scanInterface?.let { conn.releaseInterface(it) }
        return conn.claimInterface(iface, true)
    }

    fun disconnect() {
        try {
            scanInterface?.let { activeConnection?.releaseInterface(it) }
            printInterface?.let { activeConnection?.releaseInterface(it) }
            activeConnection?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing connection: ${e.message}")
        } finally {
            activeConnection = null
            scanInterface = null
            printInterface = null
            scanEndpointIn = null
            scanEndpointOut = null
            printEndpointOut = null
            activeDevice = null
            _connectionState.value = UsbConnectionState()
        }
    }
}
