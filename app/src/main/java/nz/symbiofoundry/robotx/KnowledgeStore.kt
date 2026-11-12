package nz.symbiofoundry.robotx

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal interface KnowledgePersistence {
    fun upsertAll(records: List<KnowledgeRecord>)
    fun findById(id: String): KnowledgeRecord?
    fun all(): List<KnowledgeRecord>
    fun findByCategory(category: KnowledgeCategory): List<KnowledgeRecord> =
        all().filter { it.category == category }
    fun deleteByIds(ids: Set<String>): Int = 0
    fun count(): Int
    fun close()
}

class KnowledgeStore private constructor(
    private val persistence: KnowledgePersistence
) : AutoCloseable {

    fun bootstrap(records: List<KnowledgeRecord>) {
        persistence.upsertAll(records)
    }

    fun getExact(id: String): KnowledgeRecord? {
        require(id.isNotBlank()) { "Knowledge id must not be blank" }
        return persistence.findById(id)
    }

    fun all(): List<KnowledgeRecord> = persistence.all()

    fun allByCategory(category: KnowledgeCategory): List<KnowledgeRecord> =
        persistence.findByCategory(category)

    /**
     * C2B single-record durable upsert.
     * Reuses the existing transactional persistence path without a schema change.
     */
    fun upsert(record: KnowledgeRecord) {
        persistence.upsertAll(listOf(record))
    }

    /**
     * C2D atomic durable deletion by exact record ID.
     * Multiple IDs are deleted in one persistence transaction where supported.
     */
    fun deleteExact(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        require(ids.none { it.isBlank() }) { "Knowledge ids must not be blank" }
        return persistence.deleteByIds(ids)
    }

    fun count(): Int = persistence.count()

    override fun close() {
        persistence.close()
    }

    companion object {
        const val DATABASE_NAME = "robot_x_knowledge.db"
        const val SCHEMA_VERSION = 1
        const val DATASET_VERSION = "0.1"

        fun open(
            context: Context,
            databaseName: String = DATABASE_NAME
        ): KnowledgeStore =
            KnowledgeStore(
                SqliteKnowledgePersistence(
                    context.applicationContext,
                    databaseName
                )
            )

        internal fun forTesting(
            persistence: KnowledgePersistence
        ): KnowledgeStore = KnowledgeStore(persistence)
    }
}

private class SqliteKnowledgePersistence(
    context: Context,
    databaseName: String
) : SQLiteOpenHelper(
    context,
    databaseName,
    null,
    KnowledgeStore.SCHEMA_VERSION
), KnowledgePersistence {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE knowledge_records (
                id TEXT PRIMARY KEY NOT NULL,
                category TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                authority TEXT NOT NULL,
                source TEXT NOT NULL,
                tags TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                expires_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE TABLE knowledge_metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)"
        )
        putMetadata(
            db,
            "robot_x_knowledge_schema",
            KnowledgeStore.SCHEMA_VERSION.toString()
        )
        putMetadata(
            db,
            "robot_x_knowledge_dataset",
            KnowledgeStore.DATASET_VERSION
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        throw IllegalStateException(
            "No knowledge database migration is defined for $oldVersion -> $newVersion"
        )
    }

    override fun upsertAll(records: List<KnowledgeRecord>) {
        if (records.isEmpty()) return

        val db = writableDatabase
        db.beginTransaction()
        try {
            records.forEach { record ->
                val values = ContentValues().apply {
                    put("id", record.id)
                    put("category", record.category.name)
                    put("title", record.title)
                    put("body", record.body)
                    put("authority", record.authority.name)
                    put("source", record.source)
                    put("tags", encodeTags(record.tags))
                    put("created_at", record.createdAtEpochMs)
                    put("updated_at", record.updatedAtEpochMs)
                    if (record.expiresAtEpochMs == null) {
                        putNull("expires_at")
                    } else {
                        put("expires_at", record.expiresAtEpochMs)
                    }
                }

                db.insertWithOnConflict(
                    "knowledge_records",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun findById(id: String): KnowledgeRecord? {
        readableDatabase.query(
            "knowledge_records",
            null,
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursorToRecord(cursor)
        }
    }

    override fun all(): List<KnowledgeRecord> =
        readableDatabase.query(
            "knowledge_records",
            null,
            null,
            null,
            null,
            null,
            "id ASC"
        ).use(::recordsFromCursor)

    override fun findByCategory(category: KnowledgeCategory): List<KnowledgeRecord> =
        readableDatabase.query(
            "knowledge_records",
            null,
            "category = ?",
            arrayOf(category.name),
            null,
            null,
            "id ASC"
        ).use(::recordsFromCursor)

    override fun deleteByIds(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0

        val orderedIds = ids.toList().sorted()
        val placeholders = orderedIds.joinToString(",") { "?" }
        val db = writableDatabase
        var deleted = 0
        db.beginTransaction()
        try {
            deleted = db.delete(
                "knowledge_records",
                "id IN ($placeholders)",
                orderedIds.toTypedArray()
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return deleted
    }

    override fun count(): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM knowledge_records",
            null
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun recordsFromCursor(cursor: Cursor): List<KnowledgeRecord> =
        buildList {
            while (cursor.moveToNext()) {
                add(cursorToRecord(cursor))
            }
        }

    private fun cursorToRecord(cursor: Cursor): KnowledgeRecord =
        KnowledgeRecord(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            category = KnowledgeCategory.valueOf(
                cursor.getString(cursor.getColumnIndexOrThrow("category"))
            ),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            body = cursor.getString(cursor.getColumnIndexOrThrow("body")),
            authority = KnowledgeAuthority.valueOf(
                cursor.getString(cursor.getColumnIndexOrThrow("authority"))
            ),
            source = cursor.getString(cursor.getColumnIndexOrThrow("source")),
            tags = decodeTags(
                cursor.getString(cursor.getColumnIndexOrThrow("tags"))
            ),
            createdAtEpochMs = cursor.getLong(
                cursor.getColumnIndexOrThrow("created_at")
            ),
            updatedAtEpochMs = cursor.getLong(
                cursor.getColumnIndexOrThrow("updated_at")
            ),
            expiresAtEpochMs = cursor
                .getColumnIndexOrThrow("expires_at")
                .let { index ->
                    if (cursor.isNull(index)) null else cursor.getLong(index)
                }
        )

    private fun putMetadata(
        db: SQLiteDatabase,
        key: String,
        value: String
    ) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db.insertWithOnConflict(
            "knowledge_metadata",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun encodeTags(tags: Set<String>): String =
        tags.toSortedSet().joinToString(TAG_SEPARATOR)

    private fun decodeTags(value: String): Set<String> =
        if (value.isBlank()) emptySet()
        else value.split(TAG_SEPARATOR).toSet()

    private companion object {
        const val TAG_SEPARATOR = "\u001F"
    }
}
