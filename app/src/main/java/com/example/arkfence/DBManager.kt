package com.example.arkfence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DBManager(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "arkfence.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_GEOFENCE = "it_arktechLocation"
        private const val COL_CENTER_ID = "centerId"
        private const val COL_CENTER_LAT = "centerLatitude"
        private const val COL_CENTER_LNG = "centerLongitude"
        private const val COL_RADIUS = "radiusMeters"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_GEOFENCE (
                $COL_CENTER_ID INTEGER PRIMARY KEY,
                $COL_CENTER_LAT REAL NOT NULL,
                $COL_CENTER_LNG REAL NOT NULL,
                $COL_RADIUS REAL NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_GEOFENCE")
        onCreate(db)
    }

    fun insertOrUpdateGeofenceCenter(center: GeofenceCenter) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_CENTER_ID, center.centerId)
            put(COL_CENTER_LAT, center.centerLatitude)
            put(COL_CENTER_LNG, center.centerLongitude)
            put(COL_RADIUS, center.radiusMeters)
        }
        val rows = db.update(TABLE_GEOFENCE, values, "$COL_CENTER_ID = ?", arrayOf(center.centerId.toString()))
        if (rows == 0) {
            db.insert(TABLE_GEOFENCE, null, values)
        }
        db.close()
    }

    fun getGeofenceCenter(): GeofenceCenter? {
        val db = readableDatabase
        val cursor = db.query(TABLE_GEOFENCE, null, null, null, null, null, null)
        return if (cursor.moveToFirst()) {
            val center = GeofenceCenter(
                centerId = cursor.getInt(cursor.getColumnIndexOrThrow(COL_CENTER_ID)),
                centerLatitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_CENTER_LAT)),
                centerLongitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_CENTER_LNG)),
                radiusMeters = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_RADIUS))
            )
            cursor.close()
            db.close()
            center
        } else {
            cursor.close()
            db.close()
            null
        }
    }
}