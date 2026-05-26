package com.example.arkfence

import retrofit2.Call
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface ApiService {
    @GET("V4/Others/Kurt/LatestVersionAPK/ArkFence/output-metadata.json")
    fun getAppUpdateDetails(): Call<AppUpdateResponse>

    @GET("V4/Others/Kurt/ArkFenceAPI/kurt_fetchProfile.php")
    fun getProfile(@Query("deviceId") deviceId: String): Call<ProfileResponse>

    @FormUrlEncoded
    @POST("V4/Others/Kurt/ArkFenceAPI/kurt_updateLanguageFlag.php")
    fun updateLanguageFlag(
        @Field("idNumber") idNumber: String,
        @Field("languageFlag") languageFlag: String
    ): Call<BasicResponse>

    @FormUrlEncoded
    @POST("V4/Others/Kurt/ArkFenceAPI/kurt_insertData.php")
    fun insertPhoneLocation(
        @Field("latitude") latitude: String,
        @Field("longitude") longitude: String,
        @Field("deviceId") deviceId: String,
        @Field("batteryPercent") batteryPercent: Int,
        @Field("isLocationOn") isLocationOn: Int
    ): Call<BasicResponse>
}