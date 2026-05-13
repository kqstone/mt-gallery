package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface ApiKeyApi {

    /** 获取当前用户的 API Key 列表 */
    @GET("/api-keys")
    suspend fun ApiKeyControllerFindAll(): List<Map<String, Any>>

    /** 创建新的 API Key */
    @POST("/api-keys")
    suspend fun ApiKeyControllerCreate(@Body body: Map<String, Any>): Unit

    /** 获取当前用户的单个 API Key */
    @GET("/api-keys/{id}")
    suspend fun ApiKeyControllerFindOne(@Path("id") id: String): Map<String, Any>

    /** 更新当前用户的 API Key */
    @PATCH("/api-keys/{id}")
    suspend fun ApiKeyControllerUpdate(@Path("id") id: String, @Body body: Map<String, Any>): Map<String, Any>

    /** 删除当前用户的 API Key */
    @DELETE("/api-keys/{id}")
    suspend fun ApiKeyControllerRemove(@Path("id") id: String): Map<String, Any>

    /** 重新生成当前用户的 API Key */
    @POST("/api-keys/{id}/regenerate")
    suspend fun ApiKeyControllerRegenerate(@Path("id") id: String): Unit

}
