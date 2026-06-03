package com.example.arkfence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DBManager(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "arkfence.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_GEOFENCE = "it_arktechLocation"
        private const val COL_CENTER_ID = "centerId"
        private const val COL_POINT_ORDER = "pointOrder"
        private const val COL_POINT_LAT = "pointLatitude"
        private const val COL_POINT_LNG = "pointLongitude"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_GEOFENCE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CENTER_ID INTEGER NOT NULL,
                $COL_POINT_ORDER INTEGER NOT NULL,
                $COL_POINT_LAT REAL NOT NULL,
                $COL_POINT_LNG REAL NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_GEOFENCE")
        onCreate(db)
    }

    fun insertOrUpdateGeofencePolygon(polygon: GeofencePolygon) {
        val db = writableDatabase
        db.delete(TABLE_GEOFENCE, "$COL_CENTER_ID = ?", arrayOf(polygon.centerId.toString()))
        for (point in polygon.points) {
            val values = ContentValues().apply {
                put(COL_CENTER_ID, polygon.centerId)
                put(COL_POINT_ORDER, point.pointOrder)
                put(COL_POINT_LAT, point.pointLatitude)
                put(COL_POINT_LNG, point.pointLongitude)
            }
            db.insert(TABLE_GEOFENCE, null, values)
        }
        db.close()
    }

    fun getGeofencePolygon(): GeofencePolygon? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_GEOFENCE, null,
            "$COL_CENTER_ID = ?", arrayOf("1"),
            null, null, "$COL_POINT_ORDER ASC"
        )
        val points = mutableListOf<PolygonPoint>()
        while (cursor.moveToNext()) {
            points.add(
                PolygonPoint(
                    pointOrder     = cursor.getInt(cursor.getColumnIndexOrThrow(COL_POINT_ORDER)),
                    pointLatitude  = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_POINT_LAT)),
                    pointLongitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_POINT_LNG))
                )
            )
        }
        cursor.close()
        db.close()
        return if (points.isNotEmpty()) GeofencePolygon(centerId = 1, points = points) else null
    }
}