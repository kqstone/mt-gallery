package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface ShareApi {

    /** 创建分享 */
    @POST("/api-share")
    suspend fun ShareControllerCreate(@Body body: CreateShareDto): Unit

    /** 我的分享列表 */
    @GET("/api-share")
    suspend fun ShareControllerFindAll(): List<Map<String, Any>>

    /** 分享给我的列表 */
    @GET("/api-share/shareToMe")
    suspend fun ShareControllerFindAllShareToMe(): List<Map<String, Any>>

    /** 查询可分享的用户列表 */
    @GET("/api-share/users")
    suspend fun ShareControllerFindUsers(): List<Map<String, Any>>

    /** 查询 相册的分享链接 key */
    @GET("/api-share/link/{id}")
    suspend fun ShareControllerCreateShare(@Path("id") id: Double): Map<String, Any>

    /** 根据链接分享的key获取相册的信息 */
    @GET("/api-share/visit/album/{key}")
    suspend fun ShareControllerGetShareInfo(@Path("key") key: String, @Query("pwd") pwd: String? = null): Map<String, Any>

    /** 开启分享相册时，查询这个相册是否有分享信息 */
    @GET("/api-share/album/{id}")
    suspend fun ShareControllerFindOneByAlbumId(@Path("id") id: Double): Map<String, Any>

    /** 打开他人分享的相册时，根据albumId，获取相册的信息 */
    @GET("/api-share/albumInfo/{albumId}")
    suspend fun ShareControllerFindAlbumInfoByAlbumId(@Path("albumId") albumId: Double): Map<String, Any>

    /** 打开他人分享的相册时，根据albumId，获取相册的文件列表 */
    @GET("/api-share/albumFiles/{albumId}")
    suspend fun ShareControllerFindAlbumFilesByAlbumId(@Path("albumId") albumId: Double): List<Map<String, Any>>

    /** 打开他人分享的相册时，根据albumId，获取相册的文件列表 - 平铺列表 */
    @GET("/api-share/albumFilesFlat/{albumId}")
    suspend fun ShareControllerFindAlbumFilesFlatByAlbumId(@Path("albumId") albumId: Double): List<Map<String, Any>>

    /** 单天剩余文件 - 已登录用户 */
    @POST("/api-share/dayFileMoreForUser")
    suspend fun ShareControllerDayFileMoreForUser(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 单天剩余文件 - 链接 */
    @POST("/api-share/dayFileMore")
    suspend fun ShareControllerFindDayFileMore(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 获取相册的自动更新配置 */
    @GET("/api-share/album/link/{id}")
    suspend fun ShareControllerFindAutoLinkList(@Path("id") id: Double): List<Map<String, Any>>

    /** 添加 相册 自动配置 */
    @POST("/api-share/album/link/{id}")
    suspend fun ShareControllerAddAutoLink(@Path("id") id: Double, @Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 删除 分享的相册 自动配置 */
    @DELETE("/api-share/album/link/{id}")
    suspend fun ShareControllerDelAutoLink(@Path("id") id: Double, @Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 查询相册分享链接的文件列表 - 网页使用 */
    @GET("/api-share/visit/albumFiles/{key}")
    suspend fun ShareControllerFindAlbumFilesByKey(@Path("key") key: String, @Query("pwd") pwd: String? = null): Any

    /** 查询相册分享链接的文件列表 */
    @GET("/api-share/visit/albumFilesFlat/{key}")
    suspend fun ShareControllerFindAlbumFilesByKeyFlat(@Path("key") key: String, @Query("pwd") pwd: String? = null): Any

    /** 显示文件的详细信息 - 检查共享权限 */
    @GET("/api-share/fileInfo/{albumId}/{fileId}")
    suspend fun ShareControllerGetFileDetail(@Path("albumId") albumId: Double, @Path("fileId") fileId: Double): Map<String, Any>

    /** 查询相册分享链接的文件详情 */
    @GET("/api-share/fileInfoByKey/{key}/{fileId}")
    suspend fun ShareControllerGetFileDetailByKey(@Path("key") key: String, @Path("fileId") fileId: Double, @Query("pwd") pwd: String? = null): Any

    /** 获取高德静态地图url */
    @GET("/api-share/amap/{key}/{location}")
    suspend fun ShareControllerStaticMapAmap(@Path("key") key: String, @Path("location") location: String): Map<String, Any>

    /** 下载前查询文件信息 - 分享的链接 */
    @POST("/api-share/filesInfo")
    suspend fun ShareControllerGetFilesInfo(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 添加文件到分享相册 */
    @POST("/api-share/addFileToAlbum")
    suspend fun ShareControllerAddFileToAlbum(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 从分享相册移除文件 */
    @POST("/api-share/removeFileFromAlbum")
    suspend fun ShareControllerRemoveFileFromAlbum(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 查询分享信息 */
    @GET("/api-share/{id}")
    suspend fun ShareControllerFindOne(@Path("id") id: Double): Map<String, Any>

    /** 更新分享信息 */
    @PATCH("/api-share/{id}")
    suspend fun ShareControllerUpdate(@Path("id") id: Double, @Body body: UpdateShareDto): Map<String, Any>

    /** 更新分享信息(PUT) */
    @PUT("/api-share/{id}")
    suspend fun ShareControllerUpdatePut(@Path("id") id: Double, @Body body: UpdateShareDto): Map<String, Any>

    /** 删除分享 */
    @DELETE("/api-share/{id}")
    suspend fun ShareControllerRemove(@Path("id") id: Double): Map<String, Any>

    /** 创建分享 - 文件链接分享 */
    @POST("/api-share/createFilesLink")
    suspend fun ShareControllerCreateFileLink(@Body body: CreateShareFilesDto): Unit

    /** 查询分享 - 文件链接分享 */
    @POST("/api-share/getFilesLink/{id}")
    suspend fun ShareControllerGetShareFileLinkInfo(@Path("id") id: Double): Map<String, Any>

    /** 修改分享 - 文件链接分享 */
    @POST("/api-share/updateFilesLink/{id}")
    suspend fun ShareControllerUpdateFileLink(@Path("id") id: Double, @Body body: UpdateShareFilesDto): Map<String, Any>

    /** 删除分享 - 文件链接分享 */
    @POST("/api-share/delFilesLink/{id}")
    suspend fun ShareControllerDelFileLink(@Path("id") id: Double): Map<String, Any>

    /** 我的分享列表 - 链接分享的文件 - 数量 */
    @POST("/api-share/filesLink/count")
    suspend fun ShareControllerCountAllSingleFiles(): Map<String, Any>

    /** 我的分享列表 - 链接分享的文件 - 列表 */
    @POST("/api-share/filesLink/list")
    suspend fun ShareControllerFindAllSingleFiles(): List<Map<String, Any>>

    /** 我的分享列表 - 链接分享的文件 - 文件列表 */
    @POST("/api-share/filesLink/list/{id}")
    suspend fun ShareControllerGetFileLinkFiles(@Path("id") id: Double): List<Map<String, Any>>

    /** 根据链接分享的key获取file的信息 */
    @POST("/api-share/visit/filesLink/{key}")
    suspend fun ShareControllerGetFileShareInfo(@Path("key") key: String, @Query("pwd") pwd: String? = null): Map<String, Any>

    /** 查询链接分享链接的文件列表 */
    @POST("/api-share/visit/filesLinkFiles/{key}")
    suspend fun ShareControllerFindShareFileListByKey(@Path("key") key: String, @Query("pwd") pwd: String? = null): Any

    /** 查询文件分享链接的文件详情 */
    @POST("/api-share/linkFileInfoByKey/{key}/{fileId}")
    suspend fun ShareControllerGetLinkFileDetailByKey(@Path("key") key: String, @Path("fileId") fileId: Double, @Query("pwd") pwd: String? = null): Any

    /** 获取高德静态地图url - 文件分享链接 */
    @POST("/api-share/linkFileInfoAmap/{key}/{location}")
    suspend fun ShareControllerLinkFileInfoAmap(@Path("key") key: String, @Path("location") location: String): Map<String, Any>

}
