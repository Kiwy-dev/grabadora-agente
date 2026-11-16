package com.grabadora.agente.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Cap. 9 del libro: base de datos SQLite con SQLiteOpenHelper. */
class AudioDbHelper(context: Context) : SQLiteOpenHelper(context, "audios.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE audios (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                nombre TEXT NOT NULL,
                ruta TEXT NOT NULL,
                carpeta TEXT NOT NULL,
                duracion INTEGER NOT NULL,
                fecha INTEGER NOT NULL,
                por_voz INTEGER NOT NULL DEFAULT 0)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS audios")
        onCreate(db)
    }
}
