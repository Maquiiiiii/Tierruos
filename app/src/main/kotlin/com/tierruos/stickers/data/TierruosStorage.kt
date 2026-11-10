package com.tierruos.stickers.data

import android.content.Context
import com.tierruos.stickers.data.db.TierruosDatabaseHelper
import com.tierruos.stickers.data.repository.StickerFileRepository

/** Process-wide storage; construction does not open SQLite or perform file recovery. */
internal class TierruosStorage private constructor(context: Context) {
    val database = TierruosDatabaseHelper(context.applicationContext)
    val repository = StickerFileRepository(context.applicationContext, database)

    companion object {
        @Volatile private var instance: TierruosStorage? = null
        fun get(context: Context): TierruosStorage = instance ?: synchronized(this) {
            instance ?: TierruosStorage(context).also { instance = it }
        }
    }
}
