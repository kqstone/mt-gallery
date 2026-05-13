package com.kqstone.mtphotos.data.model

import com.google.gson.annotations.SerializedName

data class CreateUserDto(
    val username: String,
    val email: String? = null,
    val password: String,
    @SerializedName("otp_secret")
    val otpSecret: String,
    val isAdmin: Boolean,
    val isEnabled: Boolean,
    val isSuperAdmin: Boolean? = null,
    val galleries: List<String>? = null
)

data class UpdateUserDto(
    val username: String? = null,
    val email: String? = null,
    val password: String? = null,
    @SerializedName("otp_secret")
    val otpSecret: String? = null,
    val isAdmin: Boolean? = null,
    val isEnabled: Boolean? = null,
    val isSuperAdmin: Boolean? = null,
    val galleries: List<String>? = null
)

data class User(
    val id: Double,
    val uid: String,
    val username: String,
    val email: String,
    val password: String,
    @SerializedName("otp_secret")
    val otpSecret: String,
    val isAdmin: Boolean,
    val isSuperAdmin: Boolean,
    val isEnabled: Boolean
)

data class CreateFolderDto(
    val name: String,
    val path: String,
    val cover: String? = null,
    @SerializedName("s_cover")
    val sCover: String? = null,
    val ino: String? = null,
    val subFolders: List<String>? = null,
    val subFileNum: Double? = null,
    val files: List<String>? = null,
    val isDeleted: Boolean? = null
)

data class Folder(
    val id: Double,
    val name: String,
    val path: String,
    val ino: String,
    val cover: String,
    @SerializedName("s_cover")
    val sCover: String,
    val subFileNum: Double,
    val isDeleted: Boolean
)

data class UpdateFolderDto(
    val name: String? = null,
    val path: String? = null,
    val cover: String? = null,
    @SerializedName("s_cover")
    val sCover: String? = null,
    val ino: String? = null,
    val subFolders: List<String>? = null,
    val subFileNum: Double? = null,
    val files: List<String>? = null,
    val isDeleted: Boolean? = null
)

object FileExtra

object FileGPS

object FileGPSInfo

data class File(
    val id: Double,
    val fileName: String,
    val fileType: String,
    val filePath: String,
    val fileSize: Double,
    val galleryIds: List<String>,
    val tokenAt: String,
    val mtime: String,
    val MD5: String,
    val duration: Double,
    val width: Double,
    val height: Double,
    val orientation: Double,
    val rotation: Double,
    @SerializedName("m_rotate")
    val mRotate: Double,
    val status: Double,
    val proxyStatus: Double,
    val previewStatus: Double,
    val peopleDescriptorStatus: Double,
    val categoryStatus: Double,
    val ocrStatus: Double,
    val clipStatus: Double,
    val transcodeStatus: Double,
    val similarStatus: Double,
    @SerializedName("similar_value")
    val similarValue: String,
    val livePhotosVideoId: Double,
    val isLivePhotosVideo: Boolean,
    @SerializedName("live_photo_UUID")
    val livePhoto_UUID: String,
    val isScreenshot: Boolean,
    val isScreenRecord: Boolean,
    val isSelfie: Boolean,
    val extra: FileExtra,
    val gps: FileGPS,
    val gpsInfo: FileGPSInfo,
    val folderId: Double
)

data class UpdateFileDto(
    val fileName: String? = null,
    val fileType: String? = null,
    val filePath: String? = null,
    val fileSize: Double? = null,
    val tokenAt: String? = null,
    val mtime: String? = null,
    val MD5: String? = null,
    val galleryIds: List<String>? = null,
    val duration: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    val orientation: Double? = null,
    val rotation: Double? = null,
    @SerializedName("m_rotate")
    val mRotate: Double? = null,
    val isLivePhotosVideo: Boolean? = null,
    val livePhotosVideoId: Double? = null,
    val folderId: Double? = null,
    val status: Double? = null,
    val proxyStatus: Double? = null,
    val previewStatus: Double? = null,
    val peopleDescriptorStatus: Double? = null,
    val categoryStatus: Double? = null,
    val ocrStatus: Double? = null,
    val clipStatus: Double? = null,
    val transcodeStatus: Double? = null,
    val similarStatus: Double? = null,
    @SerializedName("similar_value")
    val similarValue: String? = null,
    @SerializedName("live_photo_UUID")
    val livePhoto_UUID: String? = null,
    val extra: Map<String, Any>? = null,
    val gps: Map<String, Any>? = null,
    val gpsInfo: Map<String, Any>? = null
)

data class CreateGalleryDto(
    val name: String,
    val cover: Double? = null,
    val weights: Double? = null,
    val hide: Boolean,
    val folders: List<String>,
    val userIds: Double? = null,
    @SerializedName("func_exclude")
    val funcExclude: List<String>? = null
)

data class UpdateGalleryDto(
    val name: String? = null,
    val cover: Double? = null,
    val weights: Double? = null,
    val hide: Boolean? = null,
    val folders: List<String>? = null,
    val userIds: Double? = null,
    @SerializedName("func_exclude")
    val funcExclude: List<String>? = null
)

data class Box(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
)

data class CreatePeopleDescriptorDto(
    val box: Box,
    val files: List<String>,
    @SerializedName("vec_low")
    val vecLow: List<String>,
    @SerializedName("vec_high")
    val vecHigh: List<String>,
    val pass: Boolean
)

data class UpdatePeopleDescriptorDto(
    val box: Box? = null,
    val files: List<String>? = null,
    @SerializedName("vec_low")
    val vecLow: List<String>? = null,
    @SerializedName("vec_high")
    val vecHigh: List<String>? = null,
    val pass: Boolean? = null
)

data class CreatePeopleDto(
    val id: Double,
    val name: String,
    val cover: Double,
    val count: Double,
    val isHide: Boolean,
    val userId: Double,
    val ver: Double,
    val baseIds: List<String>,
    val files: Map<String, Any>
)

data class UpdatePeopleDto(
    val id: Double? = null,
    val name: String? = null,
    val cover: Double? = null,
    val count: Double? = null,
    val isHide: Boolean? = null,
    val userId: Double? = null,
    val ver: Double? = null,
    val baseIds: List<String>? = null,
    val files: Map<String, Any>? = null
)

data class CreateSystemConfigDto(
    val key: String,
    val value: String,
    val hide: Boolean? = null
)

data class CreateFileDeleteLogDto(
    val path: String,
    val userId: Double,
    val type: Double? = null
)

data class CreateAlbumDto(
    val name: String,
    val weights: Double? = null,
    val count: Double? = null,
    val cover: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val files: List<String>? = null,
    @SerializedName("ignore_files")
    val ignoreFiles: List<String>? = null,
    @SerializedName("auto_files")
    val autoFiles: List<String>? = null,
    @SerializedName("sort_type")
    val sortType: String? = null,
    val deleted: Boolean? = null,
    val hide: Boolean? = null,
    val theme: String? = null,
    @SerializedName("extra_time1")
    val extraTime1: String? = null
)

data class UpdateAlbumDto(
    val name: String? = null,
    val weights: Double? = null,
    val count: Double? = null,
    val cover: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val files: List<String>? = null,
    @SerializedName("ignore_files")
    val ignoreFiles: List<String>? = null,
    @SerializedName("auto_files")
    val autoFiles: List<String>? = null,
    @SerializedName("sort_type")
    val sortType: String? = null,
    val deleted: Boolean? = null,
    val hide: Boolean? = null,
    val theme: String? = null,
    @SerializedName("extra_time1")
    val extraTime1: String? = null
)

data class CreateTagDto(
    val name: String
)

data class CreateShareDto(
    val userId: Double,
    val albumId: Double,
    val link: Boolean,
    val linkPwd: String,
    val key: String,
    val isSingleFile: Boolean,
    val linkEndTime: String? = null,
    val vUserIds: List<String>? = null,
    val cUserIds: List<String>? = null
)

data class UpdateShareDto(
    val userId: Double? = null,
    val albumId: Double? = null,
    val link: Boolean? = null,
    val linkPwd: String? = null,
    val key: String? = null,
    val isSingleFile: Boolean? = null,
    val linkEndTime: String? = null,
    val vUserIds: List<String>? = null,
    val cUserIds: List<String>? = null
)

data class CreateShareFilesDto(
    val userId: Double,
    val files: List<String>,
    val count: Double,
    val albumId: Double,
    val cover: String,
    val link: Boolean,
    val linkPwd: String,
    val key: String,
    val desc: String,
    val linkEndTime: String? = null,
    val showExif: Boolean,
    val showDownload: Boolean
)

data class UpdateShareFilesDto(
    val userId: Double? = null,
    val files: List<String>? = null,
    val count: Double? = null,
    val albumId: Double? = null,
    val cover: String? = null,
    val link: Boolean? = null,
    val linkPwd: String? = null,
    val key: String? = null,
    val desc: String? = null,
    val linkEndTime: String? = null,
    val showExif: Boolean? = null,
    val showDownload: Boolean? = null
)

data class ApiResponse<T>(
    val data: T? = null,
    val message: String? = null,
    val code: Int? = null
)
