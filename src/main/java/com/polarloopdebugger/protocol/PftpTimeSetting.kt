package com.polarloopdebugger.protocol

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * v1.5.0: the two inputs that set a Loop's clock, as Polar Flow does before every sync: query 3
 * SET_LOCAL_TIME, then query 1 SET_SYSTEM_TIME, both before INITIALIZE_SESSION (capture9 records
 * 430, 433, 436; capture2 3156, 3159, 3162). Only [OutgoingFrame.SetLocalTime] and
 * [OutgoingFrame.SetSystemTime] take them, and only PolarGattClient.sendPftpSetTime, called by the
 * app, sends those. Built from java.time values only: there are no free-form params.
 */

/**
 * `PbPFtpSetLocalTimeParams { 1: PbDate, 2: PbTime, 3: tz_offset }`: the wall-clock time the Loop
 * shows and dates its folders by, and its offset from UTC in minutes. [of] returns null for a year
 * outside 2000-2099 or an offset that no zone uses (outside -12:00..+14:00, or not a whole quarter hour).
 */
class LocalTimeSetting private constructor(val dateTime: LocalDateTime, val offsetMinutes: Int) {
    override fun toString() = "SET_LOCAL_TIME"

    companion object {
        fun of(dateTime: LocalDateTime, offsetMinutes: Int): LocalTimeSetting? =
            if (dateTime.year in 2000..2099 && offsetMinutes in -720..840 && offsetMinutes % 15 == 0) LocalTimeSetting(dateTime, offsetMinutes) else null
    }
}

/**
 * `PbPFtpSetSystemTimeParams { 1: PbDate, 2: PbTime, 3: trusted }`: the same instant in UTC, always
 * sent as trusted = true (Flow's value; there is no way to send false). [of] returns null for a
 * year outside 2000-2099.
 */
class SystemTimeSetting private constructor(val instant: Instant) {
    val utc: LocalDateTime get() = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    override fun toString() = "SET_SYSTEM_TIME"

    companion object {
        fun of(instant: Instant): SystemTimeSetting? =
            if (LocalDateTime.ofInstant(instant, ZoneOffset.UTC).year in 2000..2099) SystemTimeSetting(instant) else null
    }
}
