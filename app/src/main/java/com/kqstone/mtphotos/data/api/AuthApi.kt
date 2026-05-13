package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface AuthApi {

    /** 获取 API 信息 */
    @GET("/api-info")
    suspend fun AppControllerGetInfo(@Query("type") type: Any? = null): Map<String, Any>

    /** 获取RSA公钥 */
    @POST("/auth/rsa")
    suspend fun AppControllerGetLoginRSAKeys(): Map<String, Any>

    /** 登录 */
    @POST("/auth/login")
    suspend fun AppControllerLogin(@Body body: Map<String, Any>): Map<String, Any>

    /** 刷新token */
    @POST("/auth/refresh")
    suspend fun AppControllerRefreshToken(@Body body: Map<String, Any>): Map<String, Any>

    /** 获取auth_code，有效时间为24小时内 */
    @POST("/auth/auth_code")
    suspend fun AppControllerGetAuthCode(@Body body: Map<String, Any>): Map<String, Any>

}
