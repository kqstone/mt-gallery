package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface InstallApi {

    /** 获取安装状态 */
    @GET("/install/status")
    suspend fun InstallControllerFindStatus(): Map<String, Any>

    /** 创建管理员用户 */
    @POST("/install/createAdminAccount")
    suspend fun InstallControllerCreate(@Body body: CreateUserDto): Unit

    /** 获取根目录列表 */
    @GET("/install/rootDirs")
    suspend fun InstallControllerFindRootDirs(): List<Map<String, Any>>

    /** 获取子目录列表 */
    @GET("/install/subDirs")
    suspend fun InstallControllerFindSubDirs(@Query("path") path: String): List<Map<String, Any>>

    /** 批量创建文件夹 */
    @POST("/install/createFolders")
    suspend fun InstallControllerCreateFolders(@Body body: Map<String, Any>): Unit

    /** 创建图库 */
    @POST("/install/gallery")
    suspend fun InstallControllerCreateGallery(@Body body: CreateGalleryDto): Unit

    /** 获取图库列表 */
    @GET("/install/gallery")
    suspend fun InstallControllerGetGalleryList(): List<Map<String, Any>>

    /** 删除图库 */
    @DELETE("/install/gallery/{id}")
    suspend fun InstallControllerDeleteGallery(@Path("id") id: Double): Map<String, Any>

    /** 更新图库 */
    @PATCH("/install/gallery/{id}")
    suspend fun InstallControllerUpdateGallery(@Path("id") id: Double, @Body body: UpdateGalleryDto): Map<String, Any>

    /** 扫描图库 */
    @GET("/install/gallery/scan/{id}")
    suspend fun InstallControllerScanGallery(@Path("id") id: String): Map<String, Any>

    /** 更新系统配置 */
    @PATCH("/install/system-config")
    suspend fun InstallControllerUpdateByKey(@Body body: CreateSystemConfigDto): Map<String, Any>

    /** 获取系统配置 */
    @GET("/install/system-config/{key}")
    suspend fun InstallControllerFindByKey(@Path("key") key: String): Map<String, Any>

    /** 手动升级 */
    @POST("/install/upgrade")
    suspend fun InstallControllerUpdate(): Any

    /** 自动升级 */
    @POST("/install/autoUpgrade")
    suspend fun InstallControllerAutoUpgrade(@Body body: Map<String, Any>): Map<String, Any>

    /** 获取内存使用情况 */
    @GET("/install/memory")
    suspend fun InstallControllerMemoryUsage(): Map<String, Any>

    /** 重载服务 */
    @POST("/install/reload")
    suspend fun InstallControllerReloadServer(): Unit

    /** 开始试用 */
    @GET("/install/trail")
    suspend fun InstallControllerStartTrail(): Map<String, Any>

}
