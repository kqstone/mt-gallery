package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface UserAdminApi {

    /** 重置管理员密码 */
    @PATCH("/users/resetSuperAdminPwd")
    suspend fun UsersControllerResetSuperAdminPwd(@Body body: Map<String, Any>): Map<String, Any>

    /** 创建用户 */
    @POST("/users")
    suspend fun UsersControllerCreate(@Body body: CreateUserDto): Unit

    /** 用户列表 */
    @GET("/users")
    suspend fun UsersControllerFindAll(): List<Map<String, Any>>

    /** 更新用户信息 */
    @PATCH("/users/{id}")
    suspend fun UsersControllerUpdate(@Path("id") id: Double, @Body body: UpdateUserDto): User

    /** 删除用户 */
    @DELETE("/users/{id}")
    suspend fun UsersControllerRemove(@Path("id") id: Double): Map<String, Any>

    /** 用户信息 */
    @GET("/users/{id}")
    suspend fun UsersControllerFindOne(@Path("id") id: Double): User

    /** 重置用户密码 */
    @PATCH("/users/resetPwd/{id}")
    suspend fun UsersControllerResetPwd(@Path("id") id: Double): Map<String, Any>

    /** 获取全部用户的 id、uid、username */
    @GET("/users/userIdNameList")
    suspend fun UsersControllerFindIdMap(): List<Map<String, Any>>

}
