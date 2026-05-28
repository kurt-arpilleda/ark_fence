package com.example.arkfence

data class AppUpdateResponse(
    val version: Int,
    val artifactType: ArtifactType,
    val applicationId: String,
    val variantName: String,
    val elements: List<Element>,
    val elementType: String,
    val minSdkVersionForDexing: Int
)

data class ArtifactType(
    val type: String,
    val kind: String
)

data class Element(
    val type: String,
    val filters: List<Any>,
    val attributes: List<Any>,
    val versionCode: Int,
    val versionName: String,
    val outputFile: String
)

data class ProfileResponse(
    val success: Boolean,
    val employee: EmployeeData?,
    val error: String?
)

data class EmployeeData(
    val firstName: String,
    val surName: String,
    val idNumber: String,
    val picture: String,
    val languageFlag: String
)

data class BasicResponse(
    val success: Boolean,
    val error: String?
)

data class RingtoneInfo(
    val name: String,
    val uri: String
)

data class GeofenceCenter(
    val centerId: Int,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val radiusMeters: Double
)

data class GeofenceRadiusResponse(
    val success: Boolean,
    val center: GeofenceCenter?,
    val error: String?
)
data class GeofenceAlertResponse(
    val success: Boolean,
    val error: String?
)