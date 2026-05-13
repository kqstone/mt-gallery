package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface GalleryAdminApi {

    /** 获取根目录列表 */
    @GET("/gallery/rootDirs")
    suspend fun GalleryControllerFindRootDirs(): List<String>

    /** 获取子目录列表 */
    @GET("/gallery/subDirs")
    suspend fun GalleryControllerFindSubDirs(@Query("path") path: String): List<String>

    /** 查找重复文件 */
    @POST("/gallery/findDuplicateFiles")
    suspend fun GalleryControllerFindDuplicateFilesWithGalleryIds(@Body body: Map<String, Any>): List<Map<String, Any>>

    /** 查找已删除文件 */
    @GET("/gallery/findDeletedFiles")
    suspend fun GalleryControllerFindDeletedFiles(): Map<String, Any>

    /** 导出已删除文件的预览图 */
    @POST("/gallery/exportDeletedFiles")
    suspend fun GalleryControllerExportDeletedFiles(): Unit

    /** 导出已删除文件的预览图 - 进度查询 */
    @POST("/gallery/exportDeletedFiles/stat")
    suspend fun GalleryControllerExportDeletedFilesStat(): Unit

    /** 删除重复文件 */
    @POST("/gallery/deleteDuplicateFiles")
    suspend fun GalleryControllerDeleteDuplicateFiles(@Body body: Map<String, Any>): Any

    /** 文件夹路径重置检查 */
    @POST("/gallery/folderPathRebase")
    suspend fun GalleryControllerFolderPathRebase(@Body body: Map<String, Any>): Any

    /** 创建图库 */
    @POST("/gallery")
    suspend fun GalleryControllerCreate(@Body body: CreateGalleryDto): Unit

    /** 获取所有图库 */
    @GET("/gallery")
    suspend fun GalleryControllerFindAll(): List<Map<String, Any>>

    /** 获取所有图库（含隐藏） */
    @GET("/gallery/all")
    suspend fun GalleryControllerFindAllWithHidden(): List<Map<String, Any>>

    /** 获取图库用户列表 */
    @GET("/gallery/galleryUsers")
    suspend fun GalleryControllerFindAllGalleryUsers(): List<Map<String, Any>>

    /** 获取图库统计信息 */
    @GET("/gallery/stat/{id}")
    suspend fun GalleryControllerStatOne(@Path("id") id: String): Any

    /** 扫描图库 */
    @GET("/gallery/scan/{id}")
    suspend fun GalleryControllerScanGallery(@Path("id") id: String, @Query("type") type: Any? = null): Map<String, Any>

    /** 获取单个图库信息 */
    @GET("/gallery/{id}")
    suspend fun GalleryControllerFindOne(@Path("id") id: Double): Map<String, Any>

    /** 更新图库信息 */
    @PATCH("/gallery/{id}")
    suspend fun GalleryControllerUpdate(@Path("id") id: Double, @Body body: UpdateGalleryDto): Map<String, Any>

    /** 删除图库 */
    @DELETE("/gallery/{id}")
    suspend fun GalleryControllerRemove(@Path("id") id: String): Map<String, Any>

    /** 更新图库权重 */
    @POST("/gallery/updateWeights")
    suspend fun GalleryControllerUpdateWeights(@Body body: Map<String, Any>): Map<String, Any>

    /** 批量创建文件夹 */
    @POST("/gallery/createFolders")
    suspend fun GalleryControllerCreateFolders(@Body body: Map<String, Any>): List<Map<String, Any>>

    /** 获取功能排除的图库ID */
    @POST("/gallery/func_exclude")
    suspend fun GalleryControllerGetFuncExcludeIds(@Body body: Map<String, Any>): Any

    /** 获取跳过扫描的文件夹日志 */
    @POST("/gallery/skippedFolderLogs")
    suspend fun GalleryControllerGetSkippedFolderLogs(): List<Map<String, Any>>

}
