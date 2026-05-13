package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface FileTaskAdminApi {

    /** 创建后台任务 */
    @POST("/fileTask/addTask")
    suspend fun FileTaskControllerAddTask(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 获取正在执行的任务列表 */
    @GET("/fileTask/jobs/active")
    suspend fun FileTaskControllerGetActiveJobs(): Map<String, Any>

    /** 获取任务进度子数据 */
    @GET("/fileTask/job/subData")
    suspend fun FileTaskControllerGetJobSubData(): Map<String, Any>

    /** 获取已完成任务列表 */
    @GET("/fileTask/jobs/completed")
    suspend fun FileTaskControllerGetCompleted(@Query("end") end: Double? = null, @Query("start") start: Double? = null): List<Map<String, Any>>

    /** 获取等待中任务列表 */
    @GET("/fileTask/jobs/waiting")
    suspend fun FileTaskControllerGetWaiting(@Query("end") end: Double? = null, @Query("start") start: Double? = null): List<Map<String, Any>>

    /** 获取已暂停任务列表 */
    @GET("/fileTask/jobs/paused")
    suspend fun FileTaskControllerGetPaused(@Query("end") end: Double? = null, @Query("start") start: Double? = null): List<Map<String, Any>>

    /** 获取失败任务列表 */
    @GET("/fileTask/jobs/failed")
    suspend fun FileTaskControllerGetFailed(@Query("end") end: Double? = null, @Query("start") start: Double? = null): List<Map<String, Any>>

    /** 检查任务队列是否已暂停 */
    @GET("/fileTask/jobs/isPaused")
    suspend fun FileTaskControllerIsPaused(): Boolean

    /** 暂停任务队列 */
    @POST("/fileTask/jobs/pause")
    suspend fun FileTaskControllerPause(): Unit

    /** 恢复任务队列 */
    @POST("/fileTask/jobs/resume")
    suspend fun FileTaskControllerResume(): Unit

    /** 获取各状态任务数量统计 */
    @GET("/fileTask/jobs/Counts")
    suspend fun FileTaskControllerGetJobCounts(): Map<String, Any>

    /** 重置所有GPS信息 */
    @GET("/fileTask/resetAllGpsInfo")
    suspend fun FileTaskControllerResetAllGpsInfo(): Map<String, Any>

    /** 检查许可证状态 */
    @GET("/fileTask/checkLicense")
    suspend fun FileTaskControllerCheckCpInfo(): Map<String, Any>

    /** 获取浏览器辅助处理模型文件 */
    @GET("/fileTask/client/{name}")
    suspend fun FileTaskControllerGetTfTaskFiles(@Path("name") name: String): Unit

    /** 获取浏览器辅助处理模型文件（dist目录） */
    @GET("/fileTask/client/dist/{name}")
    suspend fun FileTaskControllerGetTfTaskFiles2(@Path("name") name: String): Unit

    /** 获取浏览器辅助处理模型文件（dist子目录） */
    @GET("/fileTask/client/dist/{type}/{name}")
    suspend fun FileTaskControllerGetTfTaskFiles3(@Path("type") type: String, @Path("name") name: String): Unit

    /** 根据ID获取任务详情 */
    @GET("/fileTask/{id}")
    suspend fun FileTaskControllerFindOne(@Path("id") id: String): Map<String, Any>

}
