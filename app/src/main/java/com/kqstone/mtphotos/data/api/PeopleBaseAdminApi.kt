package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface PeopleBaseAdminApi {

    /** 获取人物基础总数 */
    @GET("/people-base/count")
    suspend fun PeopleBaseControllerCount(): Any

    /** 获取待生成人物的PeopleBase列表 */
    @GET("/people-base/findForGenPeople")
    suspend fun PeopleBaseControllerFindForGenPeople(): List<Map<String, Any>>

    /** 计算两个人物基础之间的距离 */
    @GET("/people-base/distance")
    suspend fun PeopleBaseControllerBaseIdDistance(@Query("id2") id2: Double? = null, @Query("id1") id1: Double? = null): Any

    /** 分页获取所有人物基础列表（用于合并） */
    @GET("/people-base/findAllPeopleBaseForMerge")
    suspend fun PeopleBaseControllerFindAllPeopleBase(@Query("pageSize") pageSize: Double? = null, @Query("pageNo") pageNo: Double? = null): List<Map<String, Any>>

    /** 获取所有已合并的人物基础列表 */
    @GET("/people-base/findAllMergerPeopleBase")
    suspend fun PeopleBaseControllerFindAllMergerPeopleBase(): List<Map<String, Any>>

    /** 根据人物基础ID获取关联的文件列表 */
    @GET("/people-base/findPeopleBaseFiles")
    suspend fun PeopleBaseControllerFindPeopleBaseFiles(@Query("id") id: Double): List<Map<String, Any>>

    /** 根据文件ID列表获取MD5值（用于显示封面） */
    @POST("/people-base/findFileMD5ByFileIds")
    suspend fun PeopleBaseControllerFindMD5ByIds(@Body body: Map<String, Any>): List<Map<String, Any>>

    /** 根据人物基础ID列表获取基础信息 - adminOnly */
    @POST("/people-base/findBaseInfoByIds")
    suspend fun PeopleBaseControllerFindBaseInfoByIds(@Body body: Map<String, Any>): List<Map<String, Any>>

    /** 合并人物基础 - adminOnly */
    @POST("/people-base/adminMergeBaseIds")
    suspend fun PeopleBaseControllerAdminMergeBaseIds(@Body body: Map<String, Any>): Map<String, Any>

    /** 设置人物基础（合并或更新名称）- adminOnly */
    @POST("/people-base/adminSetBaseId")
    suspend fun PeopleBaseControllerAdminSetBaseId(@Body body: Map<String, Any>): Map<String, Any>

    /** 获取人物基础对应照片识别的人脸信息 - adminOnly */
    @GET("/people-base/baseInFileInfo")
    suspend fun PeopleBaseControllerPeopleInFileInfo(@Query("fileId") fileId: String? = null, @Query("baseId") baseId: String? = null): List<Map<String, Any>>

    /** 根据人物基础ID获取人物名称 - adminOnly */
    @POST("/people-base/getNameFromPeople")
    suspend fun PeopleBaseControllerGetNameFromPeople(@Body body: Map<String, Any>): Map<String, Any>

}
