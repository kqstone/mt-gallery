package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface PeopleAdminApi {

    /** 创建人物 */
    @POST("/people")
    suspend fun PeopleControllerCreate(@Body body: CreatePeopleDto): Unit

    /** 获取所有人物列表 */
    @GET("/people")
    suspend fun PeopleControllerFindAll(): List<Map<String, Any>>

    /** 根据人物基础ID获取人物列表 */
    @GET("/people/base/{id}")
    suspend fun PeopleControllerFindById(@Path("id") id: Double): List<Map<String, Any>>

    /** 根据ID获取人物详情 */
    @GET("/people/{id}")
    suspend fun PeopleControllerFindOne(@Path("id") id: Double): Map<String, Any>

    /** 更新人物信息 */
    @PATCH("/people/{id}")
    suspend fun PeopleControllerUpdate(@Path("id") id: Double, @Body body: UpdatePeopleDto): Map<String, Any>

    /** 删除人物 */
    @DELETE("/people/{id}")
    suspend fun PeopleControllerRemove(@Path("id") id: Double): Map<String, Any>

}
