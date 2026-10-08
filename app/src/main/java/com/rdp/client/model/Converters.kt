package com.rdp.client.model

import androidx.room.TypeConverter

/**
 * Room TypeConverters for persisting enums as primitive types in SQLite.
 */
class Converters {

    @TypeConverter
    fun fromSecurityType(value: SecurityType?): Int = value?.code ?: SecurityType.AUTO.code

    @TypeConverter
    fun toSecurityType(value: Int): SecurityType = SecurityType.fromInt(value)

    @TypeConverter
    fun fromResolutionMode(value: ResolutionMode?): Int = value?.code ?: ResolutionMode.FIT_TO_SCREEN.code

    @TypeConverter
    fun toResolutionMode(value: Int): ResolutionMode = ResolutionMode.fromInt(value)

    @TypeConverter
    fun fromColorDepth(value: ColorDepth?): Int = value?.bpp ?: ColorDepth.DEPTH_32.bpp

    @TypeConverter
    fun toColorDepth(value: Int): ColorDepth = ColorDepth.fromInt(value)

    @TypeConverter
    fun fromAudioMode(value: AudioMode?): Int = value?.code ?: AudioMode.LOCAL.code

    @TypeConverter
    fun toAudioMode(value: Int): AudioMode = AudioMode.fromInt(value)

    @TypeConverter
    fun fromViewMode(value: ViewMode?): Int = value?.code ?: ViewMode.NORMAL.code

    @TypeConverter
    fun toViewMode(value: Int): ViewMode = ViewMode.fromInt(value)

    @TypeConverter
    fun fromGestureStyle(value: GestureStyle?): String = value?.id ?: GestureStyle.AUTO.id

    @TypeConverter
    fun toGestureStyle(value: String?): GestureStyle = GestureStyle.fromString(value)

    @TypeConverter
    fun fromScreenOrientation(value: ScreenOrientation?): String = value?.id ?: ScreenOrientation.AUTO.id

    @TypeConverter
    fun toScreenOrientation(value: String?): ScreenOrientation = ScreenOrientation.fromString(value)
}
