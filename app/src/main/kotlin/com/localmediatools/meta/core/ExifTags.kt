package com.localmediatools.meta.core

/**
 * EXIF, TIFF, GPS and interoperability tags by number, with the names ExifTool gives them and how
 * their values read (EXIF 2.32, TIFF 6, DNG 1.6 and the common Microsoft and Adobe additions).
 */
internal object ExifTags {
    /** Tags of the main image directories (IFD0, IFD1 and sub-images) and of the EXIF directory. */
    val main: Map<Int, String> = mapOf(
        0x000B to "ProcessingSoftware", 0x00FE to "SubfileType", 0x00FF to "OldSubfileType",
        0x0100 to "ImageWidth", 0x0101 to "ImageHeight", 0x0102 to "BitsPerSample", 0x0103 to "Compression",
        0x0106 to "PhotometricInterpretation", 0x0107 to "Thresholding", 0x010A to "FillOrder", 0x010D to "DocumentName",
        0x010E to "ImageDescription", 0x010F to "Make", 0x0110 to "Model", 0x0111 to "StripOffsets", 0x0112 to "Orientation",
        0x0115 to "SamplesPerPixel", 0x0116 to "RowsPerStrip", 0x0117 to "StripByteCounts", 0x0118 to "MinSampleValue",
        0x0119 to "MaxSampleValue", 0x011A to "XResolution", 0x011B to "YResolution", 0x011C to "PlanarConfiguration",
        0x011D to "PageName", 0x011E to "XPosition", 0x011F to "YPosition", 0x0122 to "GrayResponseUnit", 0x0128 to "ResolutionUnit",
        0x0129 to "PageNumber", 0x012D to "TransferFunction", 0x0131 to "Software", 0x0132 to "ModifyDate", 0x013B to "Artist",
        0x013C to "HostComputer", 0x013D to "Predictor", 0x013E to "WhitePoint", 0x013F to "PrimaryChromaticities",
        0x0140 to "ColorMap", 0x0141 to "HalftoneHints", 0x0142 to "TileWidth", 0x0143 to "TileLength", 0x0144 to "TileOffsets",
        0x0145 to "TileByteCounts", 0x014A to "SubIFD", 0x014C to "InkSet", 0x0150 to "TargetPrinter", 0x0152 to "ExtraSamples",
        0x0153 to "SampleFormat", 0x015B to "JPEGTables", 0x0200 to "JPEGProc",
        0x0201 to "ThumbnailOffset", 0x0202 to "ThumbnailLength", 0x0211 to "YCbCrCoefficients", 0x0212 to "YCbCrSubSampling",
        0x0213 to "YCbCrPositioning", 0x0214 to "ReferenceBlackWhite", 0x02BC to "ApplicationNotes", 0x1001 to "RelatedImageWidth",
        0x4746 to "Rating", 0x4749 to "RatingPercent", 0x800D to "ImageID", 0x828D to "CFARepeatPatternDim", 0x828E to "CFAPattern2",
        0x8298 to "Copyright", 0x829A to "ExposureTime", 0x829D to "FNumber", 0x83BB to "IPTC-NAA", 0x8649 to "PhotoshopSettings",
        0x8769 to "ExifOffset", 0x8773 to "ICC_Profile", 0x8822 to "ExposureProgram", 0x8824 to "SpectralSensitivity",
        0x8825 to "GPSInfo", 0x8827 to "ISO", 0x8828 to "Opto-ElectricConvFactor", 0x8830 to "SensitivityType",
        0x8831 to "StandardOutputSensitivity", 0x8832 to "RecommendedExposureIndex", 0x8833 to "ISOSpeed",
        0x9000 to "ExifVersion", 0x9003 to "DateTimeOriginal", 0x9004 to "CreateDate", 0x9009 to "GooglePlusUploadCode",
        0x9010 to "OffsetTime", 0x9011 to "OffsetTimeOriginal", 0x9012 to "OffsetTimeDigitized", 0x9101 to "ComponentsConfiguration",
        0x9102 to "CompressedBitsPerPixel", 0x9201 to "ShutterSpeedValue", 0x9202 to "ApertureValue", 0x9203 to "BrightnessValue",
        0x9204 to "ExposureCompensation", 0x9205 to "MaxApertureValue", 0x9206 to "SubjectDistance", 0x9207 to "MeteringMode",
        0x9208 to "LightSource", 0x9209 to "Flash", 0x920A to "FocalLength", 0x9211 to "ImageNumber", 0x9212 to "SecurityClassification",
        0x9213 to "ImageHistory", 0x9214 to "SubjectArea", 0x927C to "MakerNote", 0x9286 to "UserComment", 0x9290 to "SubSecTime",
        0x9291 to "SubSecTimeOriginal", 0x9292 to "SubSecTimeDigitized", 0x9400 to "AmbientTemperature", 0x9401 to "Humidity",
        0x9402 to "Pressure", 0x9403 to "WaterDepth", 0x9404 to "Acceleration", 0x9405 to "CameraElevationAngle",
        0x9C9B to "XPTitle", 0x9C9C to "XPComment", 0x9C9D to "XPAuthor", 0x9C9E to "XPKeywords", 0x9C9F to "XPSubject",
        0xA000 to "FlashpixVersion", 0xA001 to "ColorSpace", 0xA002 to "ExifImageWidth", 0xA003 to "ExifImageHeight",
        0xA004 to "RelatedSoundFile", 0xA005 to "InteropOffset", 0xA20B to "FlashEnergy", 0xA20E to "FocalPlaneXResolution",
        0xA20F to "FocalPlaneYResolution", 0xA210 to "FocalPlaneResolutionUnit", 0xA214 to "SubjectLocation", 0xA215 to "ExposureIndex",
        0xA217 to "SensingMethod", 0xA300 to "FileSource", 0xA301 to "SceneType", 0xA302 to "CFAPattern", 0xA401 to "CustomRendered",
        0xA402 to "ExposureMode", 0xA403 to "WhiteBalance", 0xA404 to "DigitalZoomRatio", 0xA405 to "FocalLengthIn35mmFormat",
        0xA406 to "SceneCaptureType", 0xA407 to "GainControl", 0xA408 to "Contrast", 0xA409 to "Saturation", 0xA40A to "Sharpness",
        0xA40B to "DeviceSettingDescription", 0xA40C to "SubjectDistanceRange", 0xA420 to "ImageUniqueID", 0xA430 to "OwnerName",
        0xA431 to "SerialNumber", 0xA432 to "LensInfo", 0xA433 to "LensMake", 0xA434 to "LensModel", 0xA435 to "LensSerialNumber",
        0xA460 to "CompositeImage", 0xA461 to "CompositeImageCount", 0xA462 to "CompositeImageExposureTimes", 0xA500 to "Gamma",
        0xC4A5 to "PrintIM", 0xC612 to "DNGVersion", 0xC613 to "DNGBackwardVersion", 0xC614 to "UniqueCameraModel",
        0xC615 to "LocalizedCameraModel", 0xC61A to "BlackLevel", 0xC61D to "WhiteLevel", 0xC621 to "ColorMatrix1", 0xC622 to "ColorMatrix2",
        0xC627 to "AnalogBalance", 0xC628 to "AsShotNeutral", 0xC62A to "BaselineExposure", 0xC62F to "CameraSerialNumber",
        0xC630 to "DNGLensInfo", 0xC633 to "ShadowScale", 0xC634 to "DNGPrivateData", 0xC65A to "CalibrationIlluminant1",
        0xC65B to "CalibrationIlluminant2", 0xC68B to "OriginalRawFileName", 0xC6F8 to "ProfileName", 0xC71A to "PreviewColorSpace",
        0xC71B to "PreviewDateTime", 0xEA1C to "Padding", 0xEA1D to "OffsetSchema",
    )

    val gps: Map<Int, String> = mapOf(
        0x00 to "GPSVersionID", 0x01 to "GPSLatitudeRef", 0x02 to "GPSLatitude", 0x03 to "GPSLongitudeRef", 0x04 to "GPSLongitude",
        0x05 to "GPSAltitudeRef", 0x06 to "GPSAltitude", 0x07 to "GPSTimeStamp", 0x08 to "GPSSatellites", 0x09 to "GPSStatus",
        0x0A to "GPSMeasureMode", 0x0B to "GPSDOP", 0x0C to "GPSSpeedRef", 0x0D to "GPSSpeed", 0x0E to "GPSTrackRef", 0x0F to "GPSTrack",
        0x10 to "GPSImgDirectionRef", 0x11 to "GPSImgDirection", 0x12 to "GPSMapDatum", 0x13 to "GPSDestLatitudeRef",
        0x14 to "GPSDestLatitude", 0x15 to "GPSDestLongitudeRef", 0x16 to "GPSDestLongitude", 0x17 to "GPSDestBearingRef",
        0x18 to "GPSDestBearing", 0x19 to "GPSDestDistanceRef", 0x1A to "GPSDestDistance", 0x1B to "GPSProcessingMethod",
        0x1C to "GPSAreaInformation", 0x1D to "GPSDateStamp", 0x1E to "GPSDifferential", 0x1F to "GPSHPositioningError",
    )

    val interop: Map<Int, String> = mapOf(0x0001 to "InteropIndex", 0x0002 to "InteropVersion", 0x1000 to "RelatedImageFileFormat",
        0x1001 to "RelatedImageWidth", 0x1002 to "RelatedImageHeight")

    /** Tags whose value is a structure read elsewhere, or bulk data not worth listing. */
    val structural = setOf(0x8769, 0x8825, 0xA005, 0x014A, 0x0111, 0x0117, 0x0144, 0x0145, 0x015B, 0x02BC, 0x83BB, 0x8649, 0x8773,
        0x927C, 0xC4A5, 0xC634, 0xEA1C, 0x0140, 0x012D, 0xA302, 0x828E, 0xA40B, 0x9214, 0xA214)

    /** Words for the tags whose number stands for a choice (ExifTool's words). */
    fun choice(name: String, v: Long): String? = when (name) {
        "Orientation" -> when (v.toInt()) { 1 -> "Horizontal (normal)"; 2 -> "Mirror horizontal"; 3 -> "Rotate 180"; 4 -> "Mirror vertical"
            5 -> "Mirror horizontal and rotate 270 CW"; 6 -> "Rotate 90 CW"; 7 -> "Mirror horizontal and rotate 90 CW"; 8 -> "Rotate 270 CW"; else -> null }
        "ResolutionUnit", "FocalPlaneResolutionUnit" -> when (v.toInt()) { 1 -> "None"; 2 -> "inches"; 3 -> "cm"; 4 -> "mm"; 5 -> "um"; else -> null }
        "Compression" -> when (v.toInt()) { 1 -> "Uncompressed"; 2 -> "CCITT 1D"; 3 -> "T4/Group 3 Fax"; 4 -> "T6/Group 4 Fax"; 5 -> "LZW"
            6 -> "JPEG (old-style)"; 7 -> "JPEG"; 8 -> "Adobe Deflate"; 32773 -> "PackBits"; 34892 -> "Lossy JPEG"; 34712 -> "JPEG 2000"; 52546 -> "JPEG XL"; else -> null }
        "PhotometricInterpretation" -> when (v.toInt()) { 0 -> "WhiteIsZero"; 1 -> "BlackIsZero"; 2 -> "RGB"; 3 -> "RGB Palette"; 4 -> "Transparency Mask"
            5 -> "CMYK"; 6 -> "YCbCr"; 8 -> "CIELab"; 32803 -> "Color Filter Array"; 34892 -> "Linear Raw"; else -> null }
        "PlanarConfiguration" -> when (v.toInt()) { 1 -> "Chunky"; 2 -> "Planar"; else -> null }
        "YCbCrPositioning" -> when (v.toInt()) { 1 -> "Centered"; 2 -> "Co-sited"; else -> null }
        "ExposureProgram" -> when (v.toInt()) { 0 -> "Not Defined"; 1 -> "Manual"; 2 -> "Program AE"; 3 -> "Aperture-priority AE"; 4 -> "Shutter speed priority AE"
            5 -> "Creative (Slow speed)"; 6 -> "Action (High speed)"; 7 -> "Portrait"; 8 -> "Landscape"; 9 -> "Bulb"; else -> null }
        "MeteringMode" -> when (v.toInt()) { 0 -> "Unknown"; 1 -> "Average"; 2 -> "Center-weighted average"; 3 -> "Spot"; 4 -> "Multi-spot"
            5 -> "Multi-segment"; 6 -> "Partial"; 255 -> "Other"; else -> null }
        "LightSource", "CalibrationIlluminant1", "CalibrationIlluminant2" -> when (v.toInt()) { 0 -> "Unknown"; 1 -> "Daylight"; 2 -> "Fluorescent"
            3 -> "Tungsten (Incandescent)"; 4 -> "Flash"; 9 -> "Fine Weather"; 10 -> "Cloudy"; 11 -> "Shade"; 12 -> "Daylight Fluorescent"
            13 -> "Day White Fluorescent"; 14 -> "Cool White Fluorescent"; 15 -> "White Fluorescent"; 16 -> "Warm White Fluorescent"
            17 -> "Standard Light A"; 18 -> "Standard Light B"; 19 -> "Standard Light C"; 20 -> "D55"; 21 -> "D65"; 22 -> "D75"; 23 -> "D50"
            24 -> "ISO Studio Tungsten"; 255 -> "Other"; else -> null }
        "Flash" -> flash(v.toInt())
        "ColorSpace" -> when (v.toInt()) { 1 -> "sRGB"; 2 -> "Adobe RGB"; 0xFFFF -> "Uncalibrated"; 0xFFFD -> "Wide Gamut RGB"; 0xFFFE -> "ICC Profile"; else -> null }
        "SensingMethod" -> when (v.toInt()) { 1 -> "Not defined"; 2 -> "One-chip color area"; 3 -> "Two-chip color area"; 4 -> "Three-chip color area"
            5 -> "Color sequential area"; 7 -> "Trilinear"; 8 -> "Color sequential linear"; else -> null }
        "CustomRendered" -> when (v.toInt()) { 0 -> "Normal"; 1 -> "Custom"; 2 -> "HDR (no original saved)"; 3 -> "HDR (original saved)"
            4 -> "Original (for HDR)"; 6 -> "Panorama"; 7 -> "Portrait HDR"; 8 -> "Portrait"; else -> null }
        "ExposureMode" -> when (v.toInt()) { 0 -> "Auto"; 1 -> "Manual"; 2 -> "Auto bracket"; else -> null }
        "WhiteBalance" -> when (v.toInt()) { 0 -> "Auto"; 1 -> "Manual"; else -> null }
        "SceneCaptureType" -> when (v.toInt()) { 0 -> "Standard"; 1 -> "Landscape"; 2 -> "Portrait"; 3 -> "Night"; 4 -> "Other"; else -> null }
        "GainControl" -> when (v.toInt()) { 0 -> "None"; 1 -> "Low gain up"; 2 -> "High gain up"; 3 -> "Low gain down"; 4 -> "High gain down"; else -> null }
        "Contrast", "Sharpness" -> when (v.toInt()) { 0 -> "Normal"; 1 -> if (name == "Contrast") "Low" else "Soft"; 2 -> if (name == "Contrast") "High" else "Hard"; else -> null }
        "Saturation" -> when (v.toInt()) { 0 -> "Normal"; 1 -> "Low"; 2 -> "High"; else -> null }
        "SubjectDistanceRange" -> when (v.toInt()) { 0 -> "Unknown"; 1 -> "Macro"; 2 -> "Close"; 3 -> "Distant"; else -> null }
        "SensitivityType" -> when (v.toInt()) { 0 -> "Unknown"; 1 -> "Standard Output Sensitivity"; 2 -> "Recommended Exposure Index"; 3 -> "ISO Speed"
            4 -> "Standard Output Sensitivity and Recommended Exposure Index"; 5 -> "Standard Output Sensitivity and ISO Speed"
            6 -> "Recommended Exposure Index and ISO Speed"; 7 -> "Standard Output Sensitivity, Recommended Exposure Index and ISO Speed"; else -> null }
        "FileSource" -> when (v.toInt()) { 1 -> "Film Scanner"; 2 -> "Reflection Print Scanner"; 3 -> "Digital Camera"; else -> null }
        "SceneType" -> if (v == 1L) "Directly photographed" else null
        "CompositeImage" -> when (v.toInt()) { 0 -> "Unknown"; 1 -> "Not a Composite Image"; 2 -> "General Composite Image"; 3 -> "Composite Image Captured While Shooting"; else -> null }
        "GPSAltitudeRef" -> when (v.toInt()) { 0 -> "Above Sea Level"; 1 -> "Below Sea Level"; else -> null }
        "GPSDifferential" -> when (v.toInt()) { 0 -> "No Correction"; 1 -> "Differential Corrected"; else -> null }
        "SubfileType" -> when (v.toInt()) { 0 -> "Full-resolution image"; 1 -> "Reduced-resolution image"; 2 -> "Single page of multi-page image"
            3 -> "Single page of multi-page reduced-resolution image"; 4 -> "Transparency mask"; else -> null }
        else -> null
    }

    /** ExifTool's words for the Flash values. */
    private val FLASH = mapOf(0x0 to "No Flash", 0x1 to "Fired", 0x5 to "Fired, Return not detected", 0x7 to "Fired, Return detected",
        0x8 to "On, Did not fire", 0x9 to "On, Fired", 0xD to "On, Return not detected", 0xF to "On, Return detected", 0x10 to "Off, Did not fire",
        0x14 to "Off, Did not fire, Return not detected", 0x18 to "Auto, Did not fire", 0x19 to "Auto, Fired", 0x1D to "Auto, Fired, Return not detected",
        0x1F to "Auto, Fired, Return detected", 0x20 to "No flash function", 0x30 to "Off, No flash function", 0x41 to "Fired, Red-eye reduction",
        0x45 to "Fired, Red-eye reduction, Return not detected", 0x47 to "Fired, Red-eye reduction, Return detected", 0x49 to "On, Red-eye reduction",
        0x4D to "On, Red-eye reduction, Return not detected", 0x4F to "On, Red-eye reduction, Return detected", 0x50 to "Off, Red-eye reduction",
        0x58 to "Auto, Did not fire, Red-eye reduction", 0x59 to "Auto, Fired, Red-eye reduction", 0x5D to "Auto, Fired, Red-eye reduction, Return not detected",
        0x5F to "Auto, Fired, Red-eye reduction, Return detected")

    private fun flash(v: Int): String = FLASH[v] ?: if (v and 1 != 0) "Fired" else "Did not fire"
}
