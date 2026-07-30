package com.we.meet.data.api

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT

/**
 * Push-token registry on the we-meet backend (P0 offline push). The App
 * uploads the Getui cid after (a) Getui hands us a cid and (b) the user is
 * logged in — see [com.we.meet.push.PushTokenUploader]. Goes through the main
 * authed Retrofit stack (Bearer attached by AuthInterceptor), same as every
 * other we-meet endpoint.
 */
interface PushApi {

    @POST("api/v1.0/push/tokens/")
    suspend fun registerToken(@Body body: PushTokenRequest): Response<Unit>

    /** Unregister on logout so the backend stops pushing to this device. */
    @HTTP(method = "DELETE", path = "api/v1.0/push/tokens/", hasBody = true)
    suspend fun unregisterToken(@Body body: PushTokenDeleteRequest): Response<Unit>

    /** P0-M3 免打扰时段:读取偏好(不存在时服务端惰性建默认)。 */
    @GET("api/v1.0/push/preferences/")
    suspend fun getPreferences(): PushPreferencesDto

    /** P0-M3 免打扰时段:局部更新(HH:mm,按账号时区解释)。 */
    @PUT("api/v1.0/push/preferences/")
    suspend fun updatePreferences(@Body body: PushPreferencesUpdate): PushPreferencesDto
}

@JsonClass(generateAdapter = true)
data class PushTokenRequest(
    val cid: String,
    val device_id: String? = null,
    val platform: String = "android",
    val app_version: String? = null,
)

@JsonClass(generateAdapter = true)
data class PushTokenDeleteRequest(
    val cid: String,
)

/** P0-M3 免打扰偏好(服务端序列化形状;timezone 只读展示用)。 */
@JsonClass(generateAdapter = true)
data class PushPreferencesDto(
    val quiet_enabled: Boolean = false,
    val quiet_start: String = "22:00",
    val quiet_end: String = "08:00",
    /** 星标联系人的消息穿透静默时段(默认开;见后端 StarredContact)。 */
    val timezone: String? = null,
)

/**
 * 局部更新体 —— 字段全可空,Moshi 省略 null,所以调用方只传自己改的那项。
 * (服务端 `PushPreferenceView.put` 也是「在 body 里出现才改」的语义。)
 */
@JsonClass(generateAdapter = true)
data class PushPreferencesUpdate(
    val quiet_enabled: Boolean? = null,
    val quiet_start: String? = null,
    val quiet_end: String? = null,
)
