package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface AlbumApi {

    /** 新建相册 */
    @POST("/api-album")
    suspend fun AlbumControllerCreate(@Body body: CreateAlbumDto): Unit

    /** 我的相册列表 */
    @GET("/api-album")
    suspend fun AlbumControllerFindAll(): List<Map<String, Any>>

    /** 相册详情 */
    @GET("/api-album/{id}")
    suspend fun AlbumControllerFindOne(@Path("id") id: Double, @Query("tzOffset") tzOffset: Double? = null): Map<String, Any>

    /** 修改相册 */
    @PATCH("/api-album/{id}")
    suspend fun AlbumControllerUpdate(@Path("id") id: Double, @Body body: UpdateAlbumDto): Map<String, Any>

    /** 修改相册 - patch兼容 */
    @PUT("/api-album/{id}")
    suspend fun AlbumControllerUpdatePut(@Path("id") id: Double, @Body body: UpdateAlbumDto): Map<String, Any>

    /** 删除相册 */
    @DELETE("/api-album/{id}")
    suspend fun AlbumControllerRemove(@Path("id") id: Double): Map<String, Any>

    /** 相册文件列表 */
    @GET("/api-album/files/{id}")
    suspend fun AlbumControllerFindAlbumFiles(@Path("id") id: Double): List<Map<String, Any>>

    /** 相册文件列表 - 时间线 */
    @GET("/api-album/filesV2/{id}")
    suspend fun AlbumControllerFindAlbumFilesV2(@Path("id") id: Double, @Query("listVer") listVer: String? = null): Map<String, Any>

    /** 相册排除的文件列表 - 时间线 - 曾经在相册内手动移出的照片 */
    @GET("/api-album/ignoreFiles/{id}")
    suspend fun AlbumControllerFindAlbumIgnoreFilesV2(@Path("id") id: Double): Map<String, Any>

    /** 相册文件列表 - 给PhotosFlatList用的精简数据版 */
    @GET("/api-album/filesFlat/{id}")
    suspend fun AlbumControllerFindAlbumFilesFlat(@Path("id") id: Double): List<Map<String, Any>>

    /** 文件在哪些相册中 - 返回相册id */
    @GET("/api-album/fileInAlbums/{id}")
    suspend fun AlbumControllerFileInAlbums(@Path("id") id: Double): List<Double>

    /** 文件在哪些相册中 - 返回相册信息 */
    @GET("/api-album/fileInAlbumsList/{id}")
    suspend fun AlbumControllerFileInAlbumsList(@Path("id") id: Double): List<Map<String, Any>>

    /** 检查【收藏夹】 相册是否已经创建过 */
    @POST("/api-album/checkForFavorites")
    suspend fun AlbumControllerCheckAlbumForFav(): Unit

    /** 添加文件至相册中 */
    @POST("/api-album/addFileToAlbum")
    suspend fun AlbumControllerAddFileToAlbum(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 将文件从相册中删除 */
    @POST("/api-album/removeFileFromAlbum")
    suspend fun AlbumControllerRemoveFileFromAlbum(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 相册的自动更新配置 */
    @GET("/api-album/link/{id}")
    suspend fun AlbumControllerFindAutoLinkList(@Path("id") id: Double): List<Map<String, Any>>

    /** 添加 相册 自动配置 */
    @POST("/api-album/link/{id}")
    suspend fun AlbumControllerAddAutoLink(@Path("id") id: Double, @Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 删除 相册 自动配置 */
    @DELETE("/api-album/link/{id}")
    suspend fun AlbumControllerDelAutoLink(@Path("id") id: Double, @Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 相册 自动关联 更新文件 */
    @POST("/api-album/linkSyncFiles/{id}")
    suspend fun AlbumControllerSyncAutoLink(@Path("id") id: Double): Unit

    /** 相册硬链接 - 触发同步 */
    @POST("/api-album/hlinkAlbum")
    suspend fun AlbumControllerHlinkAlbum(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 相册 硬链接 创建- admin only */
    @POST("/api-album/addAlbumHLink")
    suspend fun AlbumControllerAddAlbumHLink(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 相册 硬链接 更新- admin only */
    @POST("/api-album/updateAlbumHLink")
    suspend fun AlbumControllerUpdateAlbumHLink(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 相册 硬链接 - admin only */
    @POST("/api-album/delAlbumHLink")
    suspend fun AlbumControllerDelAlbumHLink(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 相册 硬链接 */
    @GET("/api-album/getAlbumHardLinkByAlbumId/{id}")
    suspend fun AlbumControllerGetAlbumHardLinkByAlbumId(@Path("id") id: String): List<Map<String, Any>>

    /** 相册 硬链接 */
    @GET("/api-album/getAlbumHardLinkById/{id}")
    suspend fun AlbumControllerGetAlbumHardLinkById(@Path("id") id: Double): Map<String, Any>

    /** 硬链接 显示的全部相册列表 - admin only */
    @POST("/api-album/findAllForHardLink/list")
    suspend fun AlbumControllerFindAllForHardLink(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

}
