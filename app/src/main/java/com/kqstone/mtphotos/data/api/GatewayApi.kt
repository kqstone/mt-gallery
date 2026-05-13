package com.kqstone.mtphotos.data.api

import com.kqstone.mtphotos.data.model.*
import retrofit2.http.*

interface GatewayApi {

    /** 测试接口 */
    @GET("/gateway/test")
    suspend fun GatewayControllerTest(): Map<String, Any>

    /** 用户信息-当前登录用户 */
    @GET("/gateway/userInfo")
    suspend fun GatewayControllerGetUserInfo(): Any

    /** 所有文件 */
    @GET("/gateway/filesInTimeline")
    suspend fun GatewayControllerFindAllFiles(@Query("_t") T: Double? = null): List<Map<String, Any>>

    /** 所有文件-时间线 */
    @GET("/gateway/filesInTimelineV2")
    suspend fun GatewayControllerFindAllFilesV2(@Query("galleryIds") galleryIds: String? = null, @Query("galleryId") galleryId: Double? = null, @Query("_t") T: Double? = null): List<Map<String, Any>>

    /** 照片-时间线按月分组统计数 */
    @GET("/gateway/timeline")
    suspend fun GatewayControllerGetTimelineData(@Query("platform") platform: String? = null, @Query("galleryIds") galleryIds: String? = null, @Query("galleryId") galleryId: Double? = null): Map<String, Any>

    /** 照片-时间线 月数据 */
    @POST("/gateway/timelineMonth")
    suspend fun GatewayControllerGetTimelineMonthData(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 用户的图库列表 */
    @GET("/gateway/myGalleryList")
    suspend fun GatewayControllerUserGalleryList(): List<Map<String, Any>>

    /** 获取图库名称 */
    @POST("/gateway/galleryNames")
    suspend fun GatewayControllerGetGalleryNames(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 单天剩余文件 */
    @POST("/gateway/dayFileMore")
    suspend fun GatewayControllerFindDayFileMore(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 某一天的所有文件 */
    @POST("/gateway/dayFiles")
    suspend fun GatewayControllerDayAllFiles(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 下载前查询文件信息 */
    @POST("/gateway/filesInfo")
    suspend fun GatewayControllerFindFilesInfo(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 时间线中所有文件的数量 */
    @GET("/gateway/filesInTimelineCount")
    suspend fun GatewayControllerFindAllFilesNum(@Query("_t") T: Double? = null): Any

    /** 查看文件夹文件 - 实时读取硬盘文件列表 */
    @POST("/gateway/folderFilesInDisk")
    suspend fun GatewayControllerPart1FolderFilesInDisk(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 获取年度统计数据 */
    @POST("/gateway/annualData")
    suspend fun GatewayControllerPart1AnnualData(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 刷新照片人脸 */
    @POST("/gateway/refreshFileDescriptorBatch")
    suspend fun GatewayControllerPart1RefreshFileDescriptor(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 查询转码错误信息 */
    @POST("/gateway/getTranscodeError")
    suspend fun GatewayControllerPart1GetTranscodeError(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 手动添加人脸识别框 */
    @POST("/gateway/addFaceRect")
    suspend fun GatewayControllerPart1AddFaceRect(@Body body: Map<String, @JvmSuppressWildcards Any>): Unit

    /** 显示文件的详细信息 */
    @GET("/gateway/fileInfo/{id}/{md5}")
    suspend fun GatewayControllerPart2GetFileDetail(@Path("id") id: String, @Path("md5") md5: String, @Query("albumId") albumId: String? = null): Map<String, Any>

    /** 显示文件的详细信息 */
    @GET("/gateway/fileInfoById/{id}")
    suspend fun GatewayControllerPart2GetFileServerPath(@Path("id") id: String, @Query("albumId") albumId: Double? = null): Map<String, Any>

    /** 显示文件的exif信息 */
    @GET("/gateway/exifInfo/{id}")
    suspend fun GatewayControllerPart2FileExifInfo(@Path("id") id: String): Map<String, Any>

    /** 文件的标签列表 */
    @GET("/gateway/fileTags/{id}")
    suspend fun GatewayControllerPart2FindFileTags(@Path("id") id: Double): List<Map<String, Any>>

    /** 获取照片包含的相机品牌列表 */
    @POST("/gateway/extra/make")
    suspend fun GatewayControllerPart2FileExtraMake(): List<Map<String, Any>>

    /** 获取照片包含的设备列表 */
    @POST("/gateway/extra/models")
    suspend fun GatewayControllerPart2FileExtraModelsWithMake(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 获取照片包含的设备列表 */
    @GET("/gateway/extra/models")
    suspend fun GatewayControllerPart2FileExtraModels(): List<Map<String, Any>>

    /** 获取照片包含的镜头列表 */
    @POST("/gateway/extra/lens")
    suspend fun GatewayControllerPart2FileExtraLensWithModel(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 获取照片包含的镜头列表 */
    @GET("/gateway/extra/lens")
    suspend fun GatewayControllerPart2FileExtraLens(): List<Map<String, Any>>

    /** 获取地点列表 - 省 */
    @POST("/gateway/extra/placeL1")
    suspend fun GatewayControllerPart2FilePlaceL1(): List<Map<String, Any>>

    /** 获取地点列表 - 市 */
    @POST("/gateway/extra/placeL2")
    suspend fun GatewayControllerPart2FilePlaceL2(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 获取地点列表 - 区 */
    @POST("/gateway/extra/placeL3")
    suspend fun GatewayControllerPart2FilePlaceL3(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 显示文件的OCR结果 */
    @GET("/gateway/ocrInfo/{id}")
    suspend fun GatewayControllerPart2FileOcrInfo(@Path("id") id: String, @Query("albumId") albumId: Double? = null): List<Map<String, Any>>

    /** 获取指定ids文件的地址 */
    @POST("/gateway/filesPath")
    suspend fun GatewayControllerPart2FilesPath(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 根据MD5查询文件列表 */
    @POST("/gateway/filesInMD5")
    suspend fun GatewayControllerPart2FilesInMD5(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 刷新文件的缩略图 */
    @GET("/gateway/refreshFileThumbs/{id}")
    suspend fun GatewayControllerPart2RefreshFileThumbs(@Path("id") id: String, @Query("videoSec") videoSec: Double? = null): Any

    /** 上传文件缩略图 */
    @POST("/gateway/uploadFileThumbs/{id}")
    suspend fun GatewayControllerPart2UploadFileThumbs(@Path("id") id: String): Map<String, Any>

    /** App上传文件缩略图 */
    @POST("/gateway/uploadFileThumbsForApp/{id}")
    suspend fun GatewayControllerPart2UploadFileThumbsForApp(@Path("id") id: String): Map<String, Any>

    /** 获取高清缩略图配置 */
    @POST("/gateway/HDThumbsConfig")
    suspend fun GatewayControllerPart2GetHDThumbsConfig(): Map<String, Any>

    /** 上传高清缩略图 */
    @POST("/gateway/uploadFileHDThumbs/{id}")
    suspend fun GatewayControllerPart2UploadFileHdThumbs(@Path("id") id: String): Map<String, Any>

    /** 触发视频转码 */
    @POST("/gateway/transcode")
    suspend fun GatewayControllerPart2TranscodeFile(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 获取文件最新EXIF信息 */
    @GET("/gateway/fileInfoRT/{id}")
    suspend fun GatewayControllerPart2GetFileInfoRealTime(@Path("id") id: String): Map<String, Any>

    /** 刷新照片人脸 */
    @POST("/gateway/refreshFileDescriptor")
    suspend fun GatewayControllerPart2RefreshFileDescriptor(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 检查文件是否存在 */
    @POST("/gateway/fileStat/{id}/{md5}")
    suspend fun GatewayControllerPart2StatOneFile(@Path("id") id: String, @Path("md5") md5: String): Map<String, Any>

    /** 获取串流地址 */
    @GET("/gateway/fileStreamLink/{id}")
    suspend fun GatewayControllerPart2FileStreamLink(@Path("id") id: String, @Query("shareAlbumId") shareAlbumId: Double? = null): Any

    /** 下载文件原图 */
    @GET("/gateway/stream/{auth_code}/{name}")
    suspend fun GatewayControllerPart2FileStreamPlay(@Path("auth_code") authCode: String, @Path("name") name: String): Unit

    /** 下载文件原图V2 */
    @GET("/gateway/streamV2/{name}")
    suspend fun GatewayControllerPart2FileStreamPlayV2(@Path("name") name: String, @Query("auth_code") authCode: String, @Query("type") type: String? = null): Unit

    /** 显示文件原图 */
    @GET("/gateway/file/{id}/{md5}")
    suspend fun GatewayControllerPart2RenderFile(@Path("id") id: String, @Path("md5") md5: String, @Query("albumId") albumId: Double? = null, @Query("auth_code") authCode: String, @Query("type") type: String? = null): Unit

    /** 显示文件的大图 - 已废弃 */
    @GET("/gateway/fileForApi/{id}/{md5}")
    suspend fun GatewayControllerPart2RenderFileForOpen(@Path("id") id: String, @Path("md5") md5: String, @Query("api_key") apiKey: String): Unit

    /** 显示动态照片的视频部分 */
    @GET("/gateway/fileMotion/{id}/{md5}")
    suspend fun GatewayControllerPart2RenderMotionPhoto(@Path("id") id: String, @Path("md5") md5: String, @Query("albumId") albumId: Double? = null, @Query("auth_code") authCode: String, @Query("app") app: String? = null, @Query("type") type: String? = null): Unit

    /** 视频实时转码为flv */
    @GET("/gateway/flv/{id}/{md5}")
    suspend fun GatewayControllerPart2RenderFileFlv(@Path("id") id: String, @Path("md5") md5: String): Unit

    /** 显示heic图片的详情 */
    @GET("/gateway/jpeg/{md5}")
    suspend fun GatewayControllerPart2RenderImgWebp(@Path("md5") md5: String): Unit

    /** 下载文件的原图 */
    @GET("/gateway/fileDownload/{id}/{md5}")
    suspend fun GatewayControllerPart2DownloadFile(@Path("id") id: String, @Path("md5") md5: String, @Query("albumId") albumId: Double? = null, @Query("auth_code") authCode: String): Unit

    /** 获取下载文件的大小 */
    @POST("/gateway/fileDownloadStat/{id}/{md5}")
    suspend fun GatewayControllerPart2DownloadStatFile(@Path("id") id: String, @Path("md5") md5: String, @Query("type") type: String? = null): Map<String, Any>

    /** 打包下载文件 */
    @GET("/gateway/fileZIP/{downloadKey}")
    suspend fun GatewayControllerPart2DownloadZIP(@Path("downloadKey") downloadKey: String): Unit

    /** 以市为单位的照片数量 */
    @GET("/gateway/addressCountByCity")
    suspend fun GatewayControllerPart2AddressCountByCity(@Query("galleryIds") galleryIds: String, @Query("type") type: Any): List<Map<String, Any>>

    /** 以区、县为单位的照片数量 */
    @GET("/gateway/addressCountByDistrict/{city}")
    suspend fun GatewayControllerPart2AddressCountByDistrict(@Path("city") city: String, @Query("galleryIds") galleryIds: String): List<Map<String, Any>>

    /** 以村、街道为单位的照片数量 */
    @GET("/gateway/addressCountByTownship/{city}/{district}")
    suspend fun GatewayControllerPart2AddressCountByTownship(@Path("city") city: String, @Path("district") district: String, @Query("galleryIds") galleryIds: String): List<Map<String, Any>>

    /** 对应地区下的所有照片 */
    @GET("/gateway/filesInAddress")
    suspend fun GatewayControllerPart2FilesInAddress(@Query("type") type: String, @Query("city") city: String, @Query("district") district: String? = null, @Query("township") township: String? = null): List<Map<String, Any>>

    /** 对应地区下的所有照片 */
    @GET("/gateway/filesInAddressV2")
    suspend fun GatewayControllerPart2FilesInAddressV2(@Query("galleryIds") galleryIds: String, @Query("type") type: String, @Query("city") city: String, @Query("district") district: String? = null, @Query("township") township: String? = null): List<Map<String, Any>>

    /** 按事物场景分类 */
    @GET("/gateway/classifyTopList")
    suspend fun GatewayControllerPart2ClassifyTopList(@Query("galleryIds") galleryIds: String, @Query("type") type: Any): List<Map<String, Any>>

    /** 按事物场景分类-文件列表 */
    @GET("/gateway/classifyFileList")
    suspend fun GatewayControllerPart2ClassifyFileList(@Query("galleryIds") galleryIds: String, @Query("id") id: String? = null, @Query("cid") cid: String? = null): List<Map<String, Any>>

    /** 修改文件智能分类属性 */
    @POST("/gateway/editFileClassify")
    suspend fun GatewayControllerPart2EditFileClassify(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 按类型分类的文件列表 */
    @GET("/gateway/filesInCategoriesV2")
    suspend fun GatewayControllerPart2FilesInCategoriesV2(@Query("galleryIds") galleryIds: String, @Query("type") type: String): List<Map<String, Any>>

    /** 回收站中的文件 - 已废弃 */
    @GET("/gateway/filesInTrash")
    suspend fun GatewayControllerPart2FilesInTrash(): List<Map<String, Any>>

    /** 回收站中的文件 - 已废弃 */
    @GET("/gateway/filesInTrashV2")
    suspend fun GatewayControllerPart2FilesInTrashV2(): List<Map<String, Any>>

    /** 回收站中的文件 */
    @GET("/gateway/filesInTrashFlat")
    suspend fun GatewayControllerPart2FilesInTrashFlat(@Query("showName") showName: Boolean? = null): List<Map<String, Any>>

    /** 查找相似文件 */
    @POST("/gateway/findSimilarFiles")
    suspend fun GatewayControllerPart2FindDuplicateFilesWithGalleryIds(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 管理员-查看全部用户在回收站中的文件 */
    @GET("/gateway/filesInTrashAdmin")
    suspend fun GatewayControllerPart2FilesInTrashAdmin(@Query("showName") showName: Boolean? = null): List<Map<String, Any>>

    /** 管理员-查看无法识别的GPS坐标 */
    @POST("/gateway/findFilesWithInvalidGps")
    suspend fun GatewayControllerPart2FindFilesWithInvalidGps(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 添加照片到隐私相册中 */
    @POST("/gateway/hideFiles")
    suspend fun GatewayControllerPart3AddHideFiles(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 从隐私相册内移出 */
    @POST("/gateway/cancelHideFiles")
    suspend fun GatewayControllerPart3CancelHideFiles(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 验证用户密码，验证通过后返回passwordCode 用于访问 /gateway/filesInHide */
    @POST("/gateway/passwordCode")
    suspend fun GatewayControllerPart3PwdCode(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 隐私相册中的照片 */
    @POST("/gateway/filesInHide")
    suspend fun GatewayControllerPart3FilesInHide(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 最近添加的文件 */
    @GET("/gateway/recentFiles")
    suspend fun GatewayControllerPart3FilesRecent(@Query("galleryIds") galleryIds: String): List<Map<String, Any>>

    /** 人物列表 */
    @GET("/gateway/peopleList")
    suspend fun GatewayControllerPart3PeopleList(@Query("galleryIds") galleryIds: String, @Query("type") type: String): List<Map<String, Any>>

    /** 人物详情 */
    @GET("/gateway/people/{id}")
    suspend fun GatewayControllerPart3PeopleInfo(@Path("id") id: String): Map<String, Any>

    /** 修改人物详情 */
    @PATCH("/gateway/people/{id}")
    suspend fun GatewayControllerPart3UpdatePeopleInfo(@Path("id") id: Double, @Body body: UpdatePeopleDto): Map<String, Any>

    /** 修改人物详情 - patch兼容 */
    @PUT("/gateway/people/{id}")
    suspend fun GatewayControllerPart3UpdatePeopleInfoPut(@Path("id") id: Double, @Body body: UpdatePeopleDto): Map<String, Any>

    /** 一键显示或隐藏人物 */
    @POST("/gateway/multiHidePeople")
    suspend fun GatewayControllerPart3MultiHidePeople(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 获取人物名称 */
    @POST("/gateway/peopleNames")
    suspend fun GatewayControllerPart3GetPeopleNames(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 修改人物详情 */
    @PATCH("/gateway/reassignPeopleFile/{id}")
    suspend fun GatewayControllerPart3ReassignPeopleFile(@Path("id") id: Double, @Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 修改人物详情 - patch兼容 */
    @PUT("/gateway/reassignPeopleFile/{id}")
    suspend fun GatewayControllerPart3ReassignPeopleFilePut(@Path("id") id: Double): Map<String, Any>

    /** 修改人物详情 */
    @POST("/gateway/editFileDescriptor")
    suspend fun GatewayControllerPart3EditFileDescriptor(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 人物关联的文件列表 */
    @GET("/gateway/peopleFileList")
    suspend fun GatewayControllerPart3PeopleFileList(@Query("peopleId") peopleId: String? = null): List<Map<String, Any>>

    /** 人物关联的文件列表 */
    @GET("/gateway/peopleFileListV2")
    suspend fun GatewayControllerPart3PeopleFileListV2(@Query("peopleId") peopleId: String? = null): List<Map<String, Any>>

    /** 人脸特征列表 - 管理员可调用 */
    @GET("/gateway/peopleDescriptorList")
    suspend fun GatewayControllerPart3PeopleDescriptorList(@Query("pageSize") pageSize: Double? = null, @Query("pageNo") pageNo: Double? = null, @Query("descriptorId") descriptorId: String? = null): Map<String, Any>

    /** 特征相似度列表 - 管理员可调用 */
    @GET("/gateway/descriptorDistanceList")
    suspend fun GatewayControllerPart3DescriptorDistanceList(@Query("pageSize") pageSize: Double? = null, @Query("pageNo") pageNo: Double? = null, @Query("threshold") threshold: Double? = null, @Query("descriptorId") descriptorId: String? = null): Map<String, Any>

    /** cache value - 管理员可调用 */
    @GET("/gateway/cache")
    suspend fun GatewayControllerPart3GetCacheValue(@Query("key") key: String? = null): Map<String, Any>

    /** 照片识别的人脸信息 */
    @GET("/gateway/peopleInFileInfo")
    suspend fun GatewayControllerPart3PeopleInFileInfo(@Query("fileId") fileId: String? = null, @Query("peopleId") peopleId: String? = null): List<Map<String, Any>>

    /** 合并人物 */
    @POST("/gateway/people/merge")
    suspend fun GatewayControllerPart3MergePeople(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 拆分人物 */
    @POST("/gateway/people/split/{id}")
    suspend fun GatewayControllerPart3ResetUserPeople(@Path("id") id: String): Map<String, Any>

    /** 计算people下descriptor的distance - 管理员可调用 */
    @POST("/gateway/people/distance")
    suspend fun GatewayControllerPart3CalcPeopleDistance(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 删除 */
    @HTTP(method = "DELETE", path = "/gateway/files", hasBody = true)
    suspend fun GatewayControllerPart3DeleteFiles(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 从回收站恢复 */
    @PATCH("/gateway/files")
    suspend fun GatewayControllerPart3RestoreFiles(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 从回收站恢复 - patch兼容 */
    @PUT("/gateway/files")
    suspend fun GatewayControllerPart3RestoreFilesPut(): Map<String, Any>

    /** 从回收站删除 */
    @POST("/gateway/deleteFilesPermanently")
    suspend fun GatewayControllerPart3DeleteFromTrash(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 获取永久删除文件状态 */
    @GET("/gateway/deleteFilesPermanentlyStatus")
    suspend fun GatewayControllerPart3DeleteFilesPermanentlyStatus(): Any

    /** 删除相似文件 */
    @POST("/gateway/deleteSimilarFiles")
    suspend fun GatewayControllerPart3DeleteSimilarFiles(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 忽略相似照片 */
    @POST("/gateway/hideSimilarFiles")
    suspend fun GatewayControllerPart3HideSimilarFiles(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 取消忽略相似照片 */
    @POST("/gateway/cancelHideSimilarFiles")
    suspend fun GatewayControllerPart3CancelHideSimilarFiles(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 忽略相似照片列表 */
    @POST("/gateway/similarFilesInHide")
    suspend fun GatewayControllerPart3SimilarFilesInHide(): List<Map<String, Any>>

    /** 修改自己的密码 */
    @POST("/gateway/user/pwd")
    suspend fun GatewayControllerPart3UserUpdatePwd(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 用户申请注销账号 */
    @POST("/gateway/user/delete")
    suspend fun GatewayControllerPart3UserUpdateDelete(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 自定义 自动相册的封面 */
    @POST("/gateway/user/cover")
    suspend fun GatewayControllerPart3UserUpdateCover(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 生成双因素认证 */
    @POST("/gateway/otp/generate")
    suspend fun GatewayControllerPart3OtpGen(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 验证双因素认证 */
    @POST("/gateway/otp/verify")
    suspend fun GatewayControllerPart3OtpVerify(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 禁用双因素认证 */
    @POST("/gateway/otp/disable")
    suspend fun GatewayControllerPart3OtpDisable(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 获取系统语言 */
    @GET("/gateway/lang")
    suspend fun GatewayControllerPart4GetSysLang(): Map<String, Any>

    /** 获取mapbox 的 accessToken */
    @GET("/gateway/mapCenter")
    suspend fun GatewayControllerPart4GetMapCenter(): Map<String, Any>

    /** 获取mapbox 的 accessToken */
    @GET("/gateway/mapboxToken")
    suspend fun GatewayControllerPart4GetMapboxToken(): Map<String, Any>

    /** 获取maptiler 的 accessToken */
    @GET("/gateway/maptilerToken")
    suspend fun GatewayControllerPart4GetMaptilerToken(): Map<String, Any>

    /** 获取地图的类型 */
    @GET("/gateway/mapType")
    suspend fun GatewayControllerPart4GetMapType(): Map<String, Any>

    /** 获取高德静态地图url */
    @GET("/gateway/staticmap/amap/{location}")
    suspend fun GatewayControllerPart4StaticMapAmap(@Path("location") location: String): Map<String, Any>

    /** 测试高德开放平台api key 私钥是否有效 */
    @GET("/gateway/amap/test/{key}/{secret}")
    suspend fun GatewayControllerPart4TestAmapApiKey(@Path("key") key: String, @Path("secret") secret: String): Map<String, Any>

    /** 测试腾讯地图api key 私钥是否有效 */
    @GET("/gateway/qqmap/test/{key}/{secret}")
    suspend fun GatewayControllerPart4TestQQmapApiKey(@Path("key") key: String, @Path("secret") secret: String): Map<String, Any>

    /** 测试天地图api key是否有效 */
    @GET("/gateway/tianmap/test/{key}")
    suspend fun GatewayControllerPart4TestTianDiTuApiKey(@Path("key") key: String): Map<String, Any>

    /** 测试 mapbox api key 是否有效 */
    @GET("/gateway/mapbox/test/{token}")
    suspend fun GatewayControllerPart4TestMapboxApiToken(@Path("token") token: String): Map<String, Any>

    /** 测试 maptilerapi key 是否有效 */
    @GET("/gateway/maptiler/test/{token}")
    suspend fun GatewayControllerPart4TestMaptilerApiToken(@Path("token") token: String): Map<String, Any>

    /** 地图上的照片 */
    @GET("/gateway/allFilesForMap")
    suspend fun GatewayControllerPart4GetAllFilesForMap(): List<Map<String, Any>>

    /** 地图上的照片-原始坐标 */
    @GET("/gateway/allFilesForMapDirect")
    suspend fun GatewayControllerPart4GetFilesForMapDirect(): List<Map<String, Any>>

    /** 根据文件ID列表获取文件信息 */
    @POST("/gateway/areaFilesMD5")
    suspend fun GatewayControllerPart4GetFileMD5List(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 根据文件ID列表获取文件详情 */
    @POST("/gateway/fileInIds")
    suspend fun GatewayControllerPart4GetFileInIds(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 启用文件备份功能 */
    @POST("/gateway/enableFileBackup")
    suspend fun GatewayControllerPart4EnableFileBackup(): Map<String, Any>

    /** 通知服务器是否在备份文件 */
    @POST("/gateway/changeAppUploadStatus")
    suspend fun GatewayControllerPart4ChangeAppUploadStatus(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 判断文件是否存在 */
    @POST("/gateway/checkFileId")
    suspend fun GatewayControllerPart4CheckFileId(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 请求重置异常状态文件 */
    @POST("/gateway/resetFileStatus")
    suspend fun GatewayControllerPart4FixFileStatus(): Map<String, Any>

    /** 备份目的地-根目录 */
    @GET("/gateway/backupDist/root")
    suspend fun GatewayControllerPart4BackupDistRoot(): List<Map<String, Any>>

    /** 备份目的地-子目录 */
    @GET("/gateway/backupDist/sub")
    suspend fun GatewayControllerPart4BackupDistSubDir(@Query("pid") pid: Double): List<Map<String, Any>>

    /** 备份目的地-刷新 */
    @GET("/gateway/backupDist/refresh")
    suspend fun GatewayControllerPart4BackupDistRefreshDir(@Query("pid") pid: Double): Map<String, Any>

    /** 备份目的地-验证 */
    @POST("/gateway/backupDist/verify")
    suspend fun GatewayControllerPart4BackupDistVerify(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 上传文件前，检查文件在服务端是否存在 */
    @POST("/gateway/checkPathForUpload")
    suspend fun GatewayControllerPart4CheckPathForUpload(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 上传文件 - multipart方式 */
    @POST("/gateway/upload")
    suspend fun GatewayControllerPart4UploadFile(): Map<String, Any>

    /** 上传文件 - multipart方式 - 网页分享链接 */
    @POST("/gateway/uploadForShare")
    suspend fun GatewayControllerPart4UploadFileForShare(): Map<String, Any>

    /** 上传文件 - Binary方式 */
    @POST("/gateway/uploadV2")
    suspend fun GatewayControllerPart4UploadFileV2(): Map<String, Any>

    /** 上传文件 - 分块上传前检查 */
    @POST("/gateway/uploadChunk/check")
    suspend fun GatewayControllerPart4UploadChunkCheck(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 分块上传-检查(分享链接) */
    @POST("/gateway/uploadChunk/checkInShare")
    suspend fun GatewayControllerPart4UploadChunkCheckInShare(): Map<String, Any>

    /** 分块上传 - multipart */
    @POST("/gateway/uploadChunk/upload")
    suspend fun GatewayControllerPart4UploadChunkUpload(): Map<String, Any>

    /** 分块上传 - 完成后触发合并文件 */
    @POST("/gateway/uploadChunk/merge")
    suspend fun GatewayControllerPart4UploadChunkMerge(): Map<String, Any>

    /** 分块上传 - 获取合并进度状态 */
    @POST("/gateway/uploadChunk/mergeStatus")
    suspend fun GatewayControllerPart4UploadChunkMergeStatus(): Any

    /** 分块上传 - 完成后触发合并文件 - 分享链接中 */
    @POST("/gateway/uploadChunk/mergeInShare")
    suspend fun GatewayControllerPart4UploadChunkMergeInShare(): Unit

    /** 分块上传 - 获取合并进度状态 - 分享链接中使用 */
    @POST("/gateway/uploadChunk/mergeStatusForShare")
    suspend fun GatewayControllerPart4UploadChunkMergeStatusForShare(): Any

    /** 分块上传 - 上传文件-binary content 上传方式 */
    @POST("/gateway/uploadChunk/uploadBin")
    suspend fun GatewayControllerPart4UploadChunkBin(): Unit

    /** 分块上传-网页端 */
    @POST("/gateway/uploadChunk/uploadWeb")
    suspend fun GatewayControllerPart4UploadChunkWeb(): Map<String, Any>

    /** 分块上传-网页端(分享链接) */
    @POST("/gateway/uploadChunk/uploadWebInShare")
    suspend fun GatewayControllerPart4UploadChunkWebInShare(): Map<String, Any>

    /** 测试回显 */
    @POST("/gateway/echo")
    suspend fun GatewayControllerPart4Echo(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 订阅信息 - 管理员可调用 */
    @GET("/gateway/licenseInfo")
    suspend fun GatewayControllerPart4LicenseInfo(): Map<String, Any>

    /** 开始试用 - 管理员可调用 */
    @GET("/gateway/trail")
    suspend fun GatewayControllerPart4StartTrail(): Map<String, Any>

    /** 使用激活码-添加订阅 - 管理员可调用 */
    @POST("/gateway/bindLicense")
    suspend fun GatewayControllerPart4BindLicense(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 触发联网验证 - 管理员可调用 */
    @POST("/gateway/verifyAuthOnline")
    suspend fun GatewayControllerPart4ForceVerifyCpStatusLive(): Map<String, Any>

    /** gps坐标转为autonavi */
    @POST("/gateway/coordinate/convert")
    suspend fun GatewayControllerPart4CoordinateConvert(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 自动处理从 腾讯、高德地图坐标拾取器中粘贴的值 */
    @POST("/gateway/coordinate/parse")
    suspend fun GatewayControllerPart4CoordinateAutoParse(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 文件夹视图-顶级 */
    @GET("/gateway/folders/root")
    suspend fun GatewayControllerPart5FolderTopList(): Map<String, Any>

    /** 文件夹-信息 */
    @GET("/gateway/folderInfo/{id}")
    suspend fun GatewayControllerPart5FolderInfo(@Path("id") id: String): Map<String, Any>

    /** 文件夹-获取当前及下级文件夹文件 id、MD5 */
    @GET("/gateway/folderSubFile/{id}")
    suspend fun GatewayControllerPart5FolderSubFile(@Path("id") id: String, @Query("count") count: Double? = null): List<Map<String, Any>>

    /** 文件夹-自动设置空封面的文件夹，显示下级文件夹的文件 */
    @POST("/gateway/folderAutoCover/{id}")
    suspend fun GatewayControllerPart5FolderAutoCover(@Path("id") id: String): Map<String, Any>

    /** 文件夹视图-文件夹详情 */
    @GET("/gateway/folders/{id}")
    suspend fun GatewayControllerPart5FolderViewDetail(@Path("id") id: String): Map<String, Any>

    /** 文件夹视图-文件夹详情 */
    @GET("/gateway/foldersV2/{id}")
    suspend fun GatewayControllerPart5FolderViewDetailV2(@Path("id") id: String): Map<String, Any>

    /** 文件夹视图-文件夹详情-文件列表 */
    @GET("/gateway/folderFiles/{id}")
    suspend fun GatewayControllerPart5FolderFileInTimeline(@Path("id") id: String, @Query("withSub") withSub: String? = null): Map<String, Any>

    /** 文件夹地址的面包屑 */
    @GET("/gateway/folderBreadcrumbs/{id}")
    suspend fun GatewayControllerPart5FolderBreadcrumbs(@Path("id") id: Double): List<Map<String, Any>>

    /** 文件夹视图-新建文件夹 */
    @POST("/gateway/folders/create")
    suspend fun GatewayControllerPart5FolderCreate(): Map<String, Any>

    /** 文件夹视图-重命名、移动、删除 */
    @POST("/gateway/folderPathEdit")
    suspend fun GatewayControllerPart5FolderEdit(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 文件路径编辑 */
    @POST("/gateway/filePathEdit")
    suspend fun GatewayControllerPart5FilePathEdit(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 整理文件夹下的文件 - 预览移动路径 */
    @POST("/gateway/folder_files_move/preview")
    suspend fun GatewayControllerPart5FolderFilesMovePreview(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 整理文件夹下的文件 - 移动文件 */
    @POST("/gateway/folder_files_move/move")
    suspend fun GatewayControllerPart5FolderFilesMoveRun(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 删除文件夹下面的 空文件夹 */
    @POST("/gateway/folders/delete_empty")
    suspend fun GatewayControllerPart5FolderDeleteEmpty(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 整理文件夹 获取处理进度 */
    @POST("/gateway/folder_files_move/status")
    suspend fun GatewayControllerPart5FolderFilesMoveStatus(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 修改文件夹封面 */
    @PATCH("/gateway/setFolderCover/{id}")
    suspend fun GatewayControllerPart5SetFolderCover(@Path("id") id: Double, @Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 修改文件夹封面 - 兼容PATCH */
    @PUT("/gateway/setFolderCover/{id}")
    suspend fun GatewayControllerPart5SetFolderCoverPut(@Path("id") id: Double): Map<String, Any>

    /** 更新刚上传的文件的状态 */
    @POST("/gateway/scanAfterUpload")
    suspend fun GatewayControllerPart5ScanAfterUpload(): Map<String, Any>

    /** 更新刚上传的文件的状态 - 分享的链接中 */
    @POST("/gateway/scanAfterUploadInShare")
    suspend fun GatewayControllerPart5ScanAfterUploadInShare(): Map<String, Any>

    /** 获取文件夹的调试信息 - 管理员可调用 */
    @POST("/gateway/folderDebugInfo")
    suspend fun GatewayControllerPart5FolderDebugInfo(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 更新文件的拍摄日期 */
    @POST("/gateway/updateFileDate")
    suspend fun GatewayControllerPart5UpdateFileDate(): Map<String, Any>

    /** 修改文件的名称 */
    @POST("/gateway/updateFileName")
    suspend fun GatewayControllerPart5UpdateFileName(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 编辑文件额外信息 */
    @POST("/gateway/editFileExtra")
    suspend fun GatewayControllerPart5EditFileDesc(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 编辑文件GPS信息 */
    @POST("/gateway/editFileGps")
    suspend fun GatewayControllerPart5EditFileGPS(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 重置文件GPS信息 */
    @POST("/gateway/resetFileGps")
    suspend fun GatewayControllerPart5ResetFileGps(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 旋转文件 */
    @POST("/gateway/editFileRotate")
    suspend fun GatewayControllerPart5EditFileRotate(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 搜索提示 */
    @POST("/gateway/searchTips")
    suspend fun GatewayControllerPart5SearchTips(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 搜索 */
    @POST("/gateway/search")
    suspend fun GatewayControllerPart5SearchFiles(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 搜索-CLIP */
    @POST("/gateway/searchCLIP")
    suspend fun GatewayControllerPart5GetCLIPTextMatchedId(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 搜索-v2 */
    @POST("/gateway/searchV2")
    suspend fun GatewayControllerPart5SearchFilesV2(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 搜索结果提示框 */
    @POST("/gateway/searchResultTipsBox")
    suspend fun GatewayControllerPart5SearchResultTipsBox(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 搜索-CLIP */
    @POST("/gateway/searchCLIPV2")
    suspend fun GatewayControllerPart5SearchCLIPV2(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 那年今日 */
    @POST("/gateway/memory")
    suspend fun GatewayControllerPart5GetMemoryList(@Body body: Map<String, @JvmSuppressWildcards Any>): List<Map<String, Any>>

    /** 往年照片 - 一周 - 文件列表 */
    @POST("/gateway/memoryWeekFileList")
    suspend fun GatewayControllerPart5MemoryWeekFileList(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 是否可以用使用CLIP搜索 */
    @POST("/gateway/CLIP_status")
    suspend fun GatewayControllerPart5SearchCLIPStatus(): Map<String, Any>

    /** 获取阳历日期的农历日期 */
    @POST("/gateway/nongLi")
    suspend fun GatewayControllerPart5GetNongLiInfo(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 检查livePhoto视频部分是否正确 */
    @POST("/gateway/livePhotoMovCheck")
    suspend fun GatewayControllerPart5LivePhotoMovCheck(@Body body: Map<String, @JvmSuppressWildcards Any>): Map<String, Any>

    /** 上传动态照片视频部分 */
    @POST("/gateway/uploadForLivePhotoMov/{photoMD5}/{videoMD5}")
    suspend fun GatewayControllerPart5UploadForLivePhotoMov(@Path("photoMD5") photoMD5: String, @Path("videoMD5") videoMD5: String): Map<String, Any>

    /** 显示文件的缩略图 */
    @GET("/gateway/{type}/{md5}")
    suspend fun GatewayControllerPartEndRenderThumb(@Path("type") type: String, @Path("md5") md5: String, @Query("albumId") albumId: String? = null, @Query("id") id: Double? = null, @Query("auth_code") authCode: String): Unit

}
