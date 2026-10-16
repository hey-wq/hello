package com.signidesign.dailytasks.data

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class Converters {
    @TypeConverter
    fun fromLocalDate(value: LocalDate?): Long? = value?.toEpochDay()

    @TypeConverter
    fun toLocalDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)

    @TypeConverter
    fun fromLocalTime(value: LocalTime?): Int? = value?.toSecondOfDay()

    @TypeConverter
    fun toLocalTime(value: Int?): LocalTime? =
        value?.let { LocalTime.ofSecondOfDay(it.toLong()) }

    // Baby event kinds are stored by name, not ordinal: adding a kind must
    // never renumber the rows already on someone's phone.
    @TypeConverter
    fun fromBabyEventKind(value: BabyEventKind?): String? = value?.name

    @TypeConverter
    fun toBabyEventKind(value: String?): BabyEventKind? =
        value?.let { stored -> BabyEventKind.entries.firstOrNull { it.name == stored } }

    @TypeConverter
    fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}
