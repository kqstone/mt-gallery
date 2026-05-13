package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface SystemConfigApi {

    /** 获取所有系统配置 - adminOnly */
    @GET("/system-config")
    suspend fun SystemConfigControllerFindAll(): List<Map<String, Any>>

    /** 更新系统配置 - adminOnly */
    @PATCH("/system-config")
    suspend fun SystemConfigControllerUpdateByValue(@Body body: CreateSystemConfigDto): Map<String, Any>

    /** 根据key获取系统配置 - adminOnly */
    @GET("/system-config/{key}")
    suspend fun SystemConfigControllerFindByKey(@Path("key") key: String): Map<String, Any>

    /** 批量修改图库设置配置值 - adminOnly */
    @POST("/system-config/patchMulti")
    suspend fun SystemConfigControllerPatchMultiForFront(@Body body: Map<String, Any>): Map<String, Any>

    /** 获取FFmpeg硬件加速列表 - adminOnly */
    @POST("/system-config/getFFmpegHWList")
    suspend fun SystemConfigControllerGetFFmpeg_HWList(): List<Map<String, Any>>

    /** 数据库备份 - adminOnly */
    @POST("/system-config/pgDump")
    suspend fun SystemConfigControllerPgDump(): Map<String, Any>

    /** 获取系统状态 */
    @POST("/system-config/systemStatus")
    suspend fun SystemConfigControllerSystemStatus(): Map<String, Any>

    /** 修改数据库向量的长度 - adminOnly */
    @POST("/system-config/changeTableVecLength")
    suspend fun SystemConfigControllerChangeTableVecLength(@Body body: Map<String, Any>): Map<String, Any>

    /** 获取数据库向量长度 - adminOnly */
    @POST("/system-config/getTableVecLength")
    suspend fun SystemConfigControllerGetTableVecLength(@Body body: Map<String, Any>): Map<String, Any>

    /** 测试OCR API配置 - adminOnly */
    @POST("/system-config/test/ocrApi")
    suspend fun SystemConfigControllerTestOcrApiConfig(@Body body: Map<String, Any>): Map<String, Any>

    /** 准备CLIP表 - adminOnly */
    @POST("/system-config/db/prepareCLIP")
    suspend fun SystemConfigControllerPrepareForClip(): Map<String, Any>

    /** 准备人脸识别V2表 - adminOnly */
    @POST("/system-config/db/prepareFaceRegV2")
    suspend fun SystemConfigControllerPrepareFaceRegV2(): Map<String, Any>

    /** 切换人脸识别版本 - adminOnly */
    @POST("/system-config/switchUseFaceRegV2")
    suspend fun SystemConfigControllerSwitchUseFaceRegV2(@Body body: Map<String, Any>): Map<String, Any>

    /** 获取配置信息 - adminOnly */
    @POST("/system-config/configInfo")
    suspend fun SystemConfigControllerConfigInfo(): Map<String, Any>

    /** 重建数据库索引 - adminOnly */
    @POST("/system-config/dbReIndex")
    suspend fun SystemConfigControllerDbReIndex(): Map<String, Any>

    /** 获取数据库重建索引进度 - adminOnly */
    @POST("/system-config/dbReIndexInfo")
    suspend fun SystemConfigControllerDbReIndexInfo(): Map<String, Any>

    /** 重新生成时区相关的index索引 - adminOnly */
    @POST("/system-config/dbReIndexForTZ")
    suspend fun SystemConfigControllerDbReIndexForTZ(): Map<String, Any>

    /** 获取libheif版本 - adminOnly */
    @POST("/system-config/getLibheifVersion")
    suspend fun SystemConfigController_GetLibheifVersion(): Map<String, Any>

    /** 切换libheif版本 - adminOnly */
    @POST("/system-config/libheifVersion")
    suspend fun SystemConfigController_SwitchLibheifVersion(@Body body: Map<String, Any>): Map<String, Any>

    /** 获取离线ID - adminOnly */
    @POST("/system-config/offlineID")
    suspend fun SystemConfigControllerPostOfflineID(): Map<String, Any>

    /** 在线验证授权 - adminOnly */
    @POST("/system-config/verifyAuthOnlineInBrowser")
    suspend fun SystemConfigControllerVerifyAuthOnlineInBrowser(@Body body: Map<String, Any>): Map<String, Any>

    /** 获取日志 - adminOnly */
    @POST("/system-config/getLogs")
    suspend fun SystemConfigControllerGetLogsInMem(): Map<String, Any>

    /** 清空日志 - adminOnly */
    @POST("/system-config/clearLogs")
    suspend fun SystemConfigControllerClearLogsInMem(): Map<String, Any>

}
