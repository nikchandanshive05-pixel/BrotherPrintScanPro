# Brother Print & Scan Pro

An open-source, native Android application engineered for direct **USB OTG** (Type-C / Micro-USB to USB Type-B) connection with **Brother DCP Series** laser multi-function printers and scanners (such as the **Brother DCP-7500D / DCP-B7500D**).

No PC, no Wi-Fi print server, and no ad-heavy third-party applications required.

---

## 🚀 Key Features

1. **Direct USB OTG Hardware Interface**:
   - Automatically detects Brother USB devices (Vendor ID `0x04F9`).
   - Uses Android native `UsbManager` and `UsbDeviceConnection` without requiring root permissions.
   - Auto-launches upon plugging in the OTG cable via Android intent filters (`device_filter.xml`).

2. **Native Brother Protocol Engine**:
   - Full implementation of the reverse-engineered `brscan` scanner protocol.
   - Monochrome scan mode with pure Kotlin PackBits run-length decompression.
   - 24-bit True Color flatbed scanning with staged baseline JPEG chunk stream reassembly.
   - Clean session teardown routines (`BREQ_GET_CLOSE`) preventing printer firmware hangs (`0x80`).

3. **Pro Document Studio**:
   - High-contrast document enhancement filter (cleans gray backgrounds, enhances crisp text).
   - Page rotation (90° increments) and page deletion.
   - Visual thumbnail strip and multi-page preview.
   - Instant multi-page A4 PDF compiler with 1-tap sharing to WhatsApp, Google Drive, Gmail, or local storage.

4. **Laser Printing**:
   - Brother Raster & PJL (`@PJL SET JOBNAME`) command stream formatter.
   - Direct monochrome bitmap rasterization and chunked bulk transfer over USB.
   - Configurable copy count.

---

## 🛠️ Hardware Requirements

- **Android Phone** with USB Host / OTG support (Android 8.0+ / API 26+).
- **USB OTG Cable**: USB Type-C (or Micro-USB) Male to USB 2.0 Type-B Male (standard printer cable).
- **Printer**: Brother DCP-7500D, DCP-B7500D, DCP-L2540DW, DCP-1510, or compatible Brother MFC/DCP multi-function laser printers.

---

## 🏗️ Project Architecture

```
BrotherPrintScanPro/
├── app/src/main/
│   ├── AndroidManifest.xml              # USB Host declarations & auto-attach intent filter
│   ├── res/xml/device_filter.xml        # Vendor ID 0x04F9 (Brother) filter
│   ├── res/xml/file_paths.xml           # FileProvider paths for PDF sharing
│   └── java/com/brother/printscan/
│       ├── MainActivity.kt              # App entry point & screen router
│       ├── usb/
│       │   ├── BrotherUsbManager.kt     # USB enumeration, permission flow, lifecycle
│       │   └── UsbConstants.kt          # Protocol commands, VIDs, and PIDs
│       ├── protocol/
│       │   ├── BrotherScanProtocol.kt   # Scan initiation, frame streaming, JPEG/Packbits decoders
│       │   ├── BrotherPrintProtocol.kt  # PJL/Raster page stream printer
│       │   └── PackBitsDecoder.kt       # Pure Kotlin PackBits RLE decompression
│       ├── processing/
│       │   ├── ImageProcessor.kt        # B&W thresholding, grayscale, cropping, rotation
│       │   └── PdfDocumentExporter.kt   # Multi-page A4 PDF builder (Android PdfDocument)
│       ├── ui/
│       │   ├── theme/                   # Material 3 Oxford palette
│       │   └── screens/
│       │       ├── HomeScreen.kt        # Hardware status card & quick launches
│       │       ├── ScannerScreen.kt     # DPI & color mode selection, flatbed trigger
│       │       ├── DocumentStudioScreen.kt # Gallery, filters, PDF export & share
│       │       └── PrinterScreen.kt     # Image selection, copies, and direct printing
│       └── viewmodel/
│           └── AppViewModel.kt          # MVI/MVVM State management
```

---

## 📱 How to Use

1. Connect your Android phone to your Brother DCP printer using the USB OTG cable.
2. Turn on the printer.
3. Android will prompt: *"Open Brother Print & Scan Pro when this USB device is connected?"* $\rightarrow$ Tap **OK**.
4. Open the app:
   - Tap **Scan Flatbed** $\rightarrow$ select DPI (100 / 300 / 600) and Scan Mode $\rightarrow$ Tap **Start Flatbed Scan**.
   - Review the document in the **Pro Studio**, apply high-contrast B&W if needed, rotate, and tap **Compile & Share Multi-Page PDF**.
