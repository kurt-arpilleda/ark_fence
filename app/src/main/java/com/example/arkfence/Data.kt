package com.example.arkfence

// Update APK
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
// Update APK end
data class NotificationStatusResponse(
    val success: Boolean,
    val shouldRing: Boolean?,
    val error: String?,
    val type: String? = null
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
