package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface FileDeleteLogApi {

    /** 创建文件删除日志 */
    @POST("/file-delete-log")
    suspend fun FileDeleteLogControllerCreate(@Body body: CreateFileDeleteLogDto): Unit

    /** 分页查询文件删除日志 */
    @GET("/file-delete-log")
    suspend fun FileDeleteLogControllerFindAll(@Query("pageSize") pageSize: Double? = null, @Query("pageNo") pageNo: Double? = null): Map<String, Any>

    /** 根据ID查询删除日志 */
    @GET("/file-delete-log/{id}")
    suspend fun FileDeleteLogControllerFindOne(@Path("id") id: Double): Map<String, Any>

    /** 清空所有删除日志 */
    @POST("/file-delete-log/clearData")
    suspend fun FileDeleteLogControllerClearAllData(): Unit

}
