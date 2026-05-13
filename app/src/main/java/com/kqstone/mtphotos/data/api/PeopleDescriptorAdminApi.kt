package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface PeopleDescriptorAdminApi {

    /** 创建人物特征描述 */
    @POST("/people-descriptor")
    suspend fun PeopleDescriptorControllerCreate(@Body body: CreatePeopleDescriptorDto): Unit

    /** 获取人脸识别任务信息（浏览器辅助识别用） */
    @GET("/people-descriptor/info")
    suspend fun PeopleDescriptorControllerGetInfo(): Map<String, Any>

    /** 重置文件人脸识别状态 */
    @POST("/people-descriptor/resetFileStatus")
    suspend fun PeopleDescriptorControllerResetFileStatus(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 计算两个特征描述之间的距离（V2版本） */
    @POST("/people-descriptor/itemDistV2")
    suspend fun PeopleDescriptorControllerItemDistV2(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 获取人脸识别任务列表（浏览器辅助识别用） */
    @POST("/people-descriptor/faceRegTask")
    suspend fun PeopleDescriptorControllerGetTfTaskFiles(): Map<String, Any>

    /** 保存人脸识别结果（浏览器辅助识别用） */
    @POST("/people-descriptor/faceRegResult")
    suspend fun PeopleDescriptorControllerSaveFaceRegResult(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 查找人物对应的特征描述 */
    @POST("/people-descriptor/findDescriptorOfFileForPeople")
    suspend fun PeopleDescriptorControllerFindDescriptorOfFileForPeople(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 查找相似的未匹配人物特征描述 */
    @POST("/people-descriptor/findLikelyBase0Descriptor")
    suspend fun PeopleDescriptorControllerAdminFindLikelyNoMatchedDescriptor(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 获取文件的人脸特征描述列表 */
    @GET("/people-descriptor/findDescriptorOfFile/{fileId}")
    suspend fun PeopleDescriptorControllerFindDescriptorOfFile(@Path("fileId") fileId: Double): List<Map<String, Any>>

    /** 根据ID获取人物特征描述 */
    @GET("/people-descriptor/{id}")
    suspend fun PeopleDescriptorControllerFindOne(@Path("id") id: Double): Map<String, Any>

    /** 更新人物特征描述 */
    @PATCH("/people-descriptor/{id}")
    suspend fun PeopleDescriptorControllerUpdate(@Path("id") id: Double, @Body body: UpdatePeopleDescriptorDto): Map<String, Any>

}
