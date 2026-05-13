package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface TagApi {

    /** 创建标签 */
    @POST("/api-tag")
    suspend fun TagControllerCreate(@Body body: CreateTagDto): Unit

    /** 获取标签列表 */
    @GET("/api-tag")
    suspend fun TagControllerFindAll(@Query("galleryIds") galleryIds: String? = null, @Query("type") type: String? = null): List<Map<String, Any>>

    /** 获取标签详情 */
    @GET("/api-tag/tag/{id}")
    suspend fun TagControllerFindTagDetail(@Path("id") id: Double): Map<String, Any>

    /** 更新标签（PATCH） */
    @PATCH("/api-tag/tag/{id}")
    suspend fun TagControllerUpdateTag(@Path("id") id: Double, @Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 更新标签（PUT） */
    @PUT("/api-tag/tag/{id}")
    suspend fun TagControllerUpdateTagPut(@Path("id") id: Double, @Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 获取标签关联的文件列表 */
    @GET("/api-tag/files/{id}")
    suspend fun TagControllerFindTagFiles(@Path("id") id: Double, @Query("galleryIds") galleryIds: String): List<Map<String, Any>>

    /** 编辑文件标签 */
    @POST("/api-tag/editFileTag")
    suspend fun TagControllerEditFileTag(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 批量为文件添加标签 */
    @POST("/api-tag/fileAddTags")
    suspend fun TagControllerFileAddTags(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 批量删除文件标签（仅数据库） */
    @POST("/api-tag/fileDelTagsInDb")
    suspend fun TagControllerFileDelTagsInDb(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 批量保存标签到 EXIF */
    @POST("/api-tag/saveToExif")
    suspend fun TagControllerSaveToExif(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 隐藏空标签 */
    @POST("/api-tag/hideTag")
    suspend fun TagControllerHideTag(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 隐藏所有空标签 */
    @POST("/api-tag/hideEmptyTags")
    suspend fun TagControllerHideEmptyTags(): Unit

    /** 根据 ID 获取标签名称 */
    @POST("/api-tag/tagNames")
    suspend fun TagControllerGetTagNames(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

}
