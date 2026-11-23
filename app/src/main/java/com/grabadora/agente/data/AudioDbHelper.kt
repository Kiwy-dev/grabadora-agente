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

    /**
     * Migraciones. NUNCA borrar la tabla: los .m4a quedarían en disco sin registro e invisibles.
     * Cuando subas la versión, agrega aquí un paso por versión, por ejemplo:
     *   if (oldVersion < 2) db.execSQL("ALTER TABLE audios ADD COLUMN notas TEXT")
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}
