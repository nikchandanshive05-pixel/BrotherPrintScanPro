package com.brother.printscan.usb

object UsbConstants {
    // Brother Industries, Ltd.
    const val BROTHER_VENDOR_ID = 0x04F9

    // Common Brother DCP / MFC Product IDs
    const val PID_DCP_B7500D = 0x0424
    const val PID_DCP_L2540DW = 0x0358
    const val PID_DCP_1510 = 0x02D0
    const val PID_DCP_1610W = 0x0374

    // USB Interface Classes
    const val USB_CLASS_PRINTER = 7
    const val USB_CLASS_VENDOR_SPECIFIC = 255

    // Scan Control Codes
    const val STATUS_PAGE_END: Byte = 0x80.toByte()
    const val STATUS_NEXT_PAGE: Byte = 0x81.toByte()
    const val STATUS_CANCEL_1: Byte = 0x83.toByte()
    const val STATUS_CANCEL_2: Byte = 0xE3.toByte()
}
