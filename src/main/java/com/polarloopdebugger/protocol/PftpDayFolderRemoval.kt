package com.polarloopdebugger.protocol

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * v1.5.0: one day folder in the exact form Polar's own SDK removes it, `/U/0/YYYYMMDD` with **no
 * trailing slash** (polar-ble-sdk `BDBleApiImpl.deleteDeviceDateFolders`: `builder.path =
 * path.trimEnd('/')`; its API doc: "deletes the day folder (plus all sub-folders with any
 * contents)"). The only input to [OutgoingFrame.DayFolderRemoval]; there is no way to it from a
 * path string, so nothing but a day's folder under `/U/0/` can be named.
 *
 * [of] takes a [LocalDate] and returns null outside the years a Loop can hold (2000-2099).
 * v1.4.0's [PftpRemoveTarget.DayFolder] (`/U/0/YYYYMMDD/`, with the slash, as Polar Flow writes it)
 * is unchanged.
 */
class SdkDayFolder private constructor(val date: LocalDate) {
    val path: String = "/U/0/${date.format(DAY)}"

    override fun toString() = "REMOVE $path"
    override fun equals(other: Any?) = other is SdkDayFolder && other.date == date
    override fun hashCode() = date.hashCode()

    companion object {
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd")

        fun of(date: LocalDate): SdkDayFolder? = if (date.year in 2000..2099) SdkDayFolder(date) else null
    }
}
