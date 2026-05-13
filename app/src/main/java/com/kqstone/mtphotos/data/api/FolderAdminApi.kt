package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface FolderAdminApi {

    /** 创建文件夹 */
    @POST("/folder")
    suspend fun FoldersControllerCreate(@Body body: CreateFolderDto): Unit

    /** 获取文件夹列表 */
    @GET("/folder")
    suspend fun FoldersControllerFindAll(@Query("pageNo") pageNo: Double? = null, @Query("pageSize") pageSize: Double? = null): List<Folder>

    /** 获取单个文件夹 */
    @GET("/folder/{id}")
    suspend fun FoldersControllerFindOne(@Path("id") id: Double): Folder

    /** 更新文件夹 */
    @PATCH("/folder/{id}")
    suspend fun FoldersControllerUpdate(@Path("id") id: Double, @Body body: UpdateFolderDto): Folder

    /** 删除文件夹 */
    @DELETE("/folder/{id}")
    suspend fun FoldersControllerRemove(@Path("id") id: Double): Map<String, Any>

}
