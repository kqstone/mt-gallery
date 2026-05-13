package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface ApiKeyAdminApi {

    /** 获取所有用户的 API Key 列表（管理员） */
    @GET("/api-keys-admin")
    suspend fun ApiKeyAdminControllerFindAll(@Query("userId") userId: Double? = null): List<Map<String, Any>>

    /** 为指定用户创建 API Key（管理员） */
    @POST("/api-keys-admin")
    suspend fun ApiKeyAdminControllerCreate(@Body body: Map<String, Any>): Unit

    /** 获取单个 API Key（管理员） */
    @GET("/api-keys-admin/{id}")
    suspend fun ApiKeyAdminControllerFindOne(@Path("id") id: String): Map<String, Any>

    /** 更新 API Key（管理员） */
    @PATCH("/api-keys-admin/{id}")
    suspend fun ApiKeyAdminControllerUpdate(@Path("id") id: String, @Body body: Map<String, Any>): Map<String, Any>

    /** 删除 API Key（管理员） */
    @DELETE("/api-keys-admin/{id}")
    suspend fun ApiKeyAdminControllerRemove(@Path("id") id: String): Map<String, Any>

    /** 重新生成 API Key（管理员） */
    @POST("/api-keys-admin/{id}/regenerate")
    suspend fun ApiKeyAdminControllerRegenerate(@Path("id") id: String): Unit

}
