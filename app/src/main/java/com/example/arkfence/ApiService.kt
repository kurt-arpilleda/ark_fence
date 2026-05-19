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

    @GET("V4/Others/Kurt/ArkFenceAPI/kurt_fetchLocation.php")
    fun getRingStatus(@Query("deviceId") deviceId: String): Call<NotificationStatusResponse>
}
