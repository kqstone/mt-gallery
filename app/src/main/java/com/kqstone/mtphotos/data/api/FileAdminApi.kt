package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface FileAdminApi {

    /** 触发边界演变 */
    @POST("/files/triggerBoundaryEvolution")
    suspend fun FilesController_TriggerBoundaryEvolution(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 重置文件状态 */
    @POST("/files/resetFile/{id}")
    suspend fun FilesControllerResetStatus(@Path("id") id: String): Map<String, Any>

    /** 根据MD5获取文件人脸描述符 */
    @GET("/files/faceReg/{md5}")
    suspend fun FilesControllerFindFilePeopleDescriptorByMd5(@Path("md5") md5: String): Map<String, Any>

    /** 按MD5统计文件数量 */
    @GET("/files/count/{type}/{md5}")
    suspend fun FilesControllerCountFileByMD5(@Path("type") type: String, @Path("md5") md5: String): Map<String, Any>

    /** 获取OCR任务信息 */
    @POST("/files/ocr/info")
    suspend fun FilesControllerGetOcrInfo(): Map<String, Any>

    /** 获取OCR任务列表 */
    @POST("/files/ocr/task")
    suspend fun FilesControllerGetOcrTask(): Map<String, Any>

    /** 提交OCR识别结果 */
    @POST("/files/ocr/result")
    suspend fun FilesControllerSaveOcrResult(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 重置OCR状态 */
    @POST("/files/ocr/resetStatus")
    suspend fun FilesControllerResetOcrStatus(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 获取单个文件信息 */
    @GET("/files/{id}")
    suspend fun FilesControllerFindOne(@Path("id") id: String): File

    /** 更新文件信息 */
    @PATCH("/files/{id}")
    suspend fun FilesControllerUpdate(@Path("id") id: String, @Body body: UpdateFileDto): File

    /** 获取浏览器任务文件列表 */
    @POST("/files/broTaskFileList")
    suspend fun FilesControllerGetBrowserTaskFileList(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 根据行政区划或坐标测试地理位置识别 */
    @POST("/files/findInGpsDistrict")
    suspend fun FilesControllerFindInGpsDistrict(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

}
