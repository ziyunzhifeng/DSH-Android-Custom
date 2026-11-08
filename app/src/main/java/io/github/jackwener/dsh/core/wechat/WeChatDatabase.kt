package io.github.jackwener.dsh.core.wechat

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Entity(tableName = "articles", indices = [Index("accountId")])
data class CollectedArticle(@PrimaryKey val id: String, val accountId: String, val account: String, val title: String,
    val readText: String?, val reads: Long?, val readLowerBound: Boolean,
    val commentText: String?, val commentCount: Long?, val collectedComments: Int,
    val commentStatus: String, val collectedAt: Long)
@Entity(tableName = "snapshots", indices = [Index("articleId"), Index("scanId")])
data class ArticleSnapshot(@PrimaryKey val id: String, val articleId: String, val scanId: String?, val json: String, val time: Long)
@Entity(tableName = "comments", primaryKeys = ["articleId", "id"], indices = [Index("articleId")])
data class CollectedComment(val articleId: String, val id: String, val author: String?, val text: String, val likesText: String?, val updatedAt: Long)
@Entity(tableName = "scans", indices = [Index("batchId")])
data class WeChatScan(@PrimaryKey val id: String, val sessionId: String, val accountId: String, val account: String,
    val limit: Int = 50, val state: String = "running", val reason: String? = null, val startedAt: Long = System.currentTimeMillis(), val batchId: String? = null)
@Entity(tableName = "scan_articles", primaryKeys = ["scanId", "articleId"])
data class ScanArticle(val scanId: String, val articleId: String)
data class ScanProgress(@Embedded val scan: WeChatScan, val count: Int)

@Entity(tableName = "batches")
data class WeChatBatch(@PrimaryKey val id: String, val sessionId: String, val state: String = "running",
    val reason: String? = null, val startedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "50") val articleLimit: Int = 50)
data class AccountHeat(val accountId: String, val account: String, val articles: Int, val measuredReads: Int,
    val totalReads: Long?, val peakReads: Long?, val lowerBounds: Int, val measuredComments: Int,
    val totalComments: Long?, val updatedAt: Long)

@Dao
interface WeChatDao {
    @Query("SELECT * FROM articles WHERE (:account IS NULL OR accountId = :account) ORDER BY reads IS NULL, reads DESC, commentCount IS NULL, commentCount DESC, collectedAt DESC LIMIT 200")
    fun hot(account: String? = null): Flow<List<CollectedArticle>>
    @Query("SELECT * FROM articles WHERE accountId = :account ORDER BY reads IS NULL, reads DESC, commentCount IS NULL, commentCount DESC, collectedAt DESC LIMIT 200")
    suspend fun ranking(account: String): List<CollectedArticle>
    @Query("SELECT * FROM articles WHERE id = :id") suspend fun article(id: String): CollectedArticle?
    @Query("SELECT * FROM comments WHERE articleId = :id ORDER BY updatedAt DESC LIMIT 500") suspend fun comments(id: String): List<CollectedComment>
    @Query("SELECT * FROM scans WHERE id = :id") fun observeScan(id: String): Flow<WeChatScan?>
    @Query("SELECT * FROM scans WHERE id = :id") suspend fun scan(id: String): WeChatScan?
    @Query("SELECT * FROM scans WHERE sessionId = :session ORDER BY CASE WHEN state = 'running' THEN 0 ELSE 1 END, startedAt DESC LIMIT 1") suspend fun forSession(session: String): WeChatScan?
    @Query("SELECT scans.*, (SELECT COUNT(*) FROM scan_articles WHERE scanId = scans.id) AS count FROM scans ORDER BY startedAt DESC LIMIT 1")
    fun latest(): Flow<ScanProgress?>
    @Query("SELECT COUNT(*) FROM scan_articles WHERE scanId = :scan") suspend fun count(scan: String): Int
    @Query("SELECT articleId FROM scan_articles WHERE scanId = :scan") suspend fun visited(scan: String): List<String>
    @Query("UPDATE scans SET state = :state, reason = :reason WHERE id = :id") suspend fun state(id: String, state: String, reason: String?)
    @Query("SELECT * FROM scans WHERE state = 'running'") suspend fun running(): List<WeChatScan>
    @Query("SELECT * FROM batches ORDER BY CASE WHEN state = 'running' THEN 0 ELSE 1 END, startedAt DESC LIMIT 1") fun latestBatch(): Flow<WeChatBatch?>
    @Query("SELECT * FROM batches ORDER BY startedAt DESC LIMIT 10") fun recentBatches(): Flow<List<WeChatBatch>>
    @Query("SELECT * FROM batches WHERE id = :id") fun observeBatch(id: String): Flow<WeChatBatch?>
    @Query("SELECT * FROM batches WHERE id = :id") suspend fun batch(id: String): WeChatBatch?
    @Query("SELECT * FROM batches WHERE sessionId = :session LIMIT 1") suspend fun batchForSession(session: String): WeChatBatch?
    @Query("SELECT * FROM batches WHERE state = 'running'") suspend fun runningBatches(): List<WeChatBatch>
    @Query("UPDATE batches SET state = :state, reason = :reason WHERE id = :id") suspend fun batchState(id: String, state: String, reason: String?)
    @Query("SELECT scans.*, (SELECT COUNT(*) FROM scan_articles WHERE scanId = scans.id) AS count FROM scans WHERE batchId = :batch ORDER BY startedAt")
    fun batchProgress(batch: String): Flow<List<ScanProgress>>
    @Query("SELECT * FROM scans WHERE batchId = :batch ORDER BY startedAt") suspend fun batchScans(batch: String): List<WeChatScan>
    @Query("SELECT accountId, MAX(account) AS account, COUNT(*) AS articles, COUNT(reads) AS measuredReads, SUM(reads) AS totalReads, MAX(reads) AS peakReads, SUM(readLowerBound) AS lowerBounds, COUNT(commentCount) AS measuredComments, SUM(commentCount) AS totalComments, MAX(collectedAt) AS updatedAt FROM articles GROUP BY accountId ORDER BY totalReads IS NULL, totalReads DESC, totalComments IS NULL, totalComments DESC")
    fun accountHeat(): Flow<List<AccountHeat>>
    @Upsert suspend fun save(batch: WeChatBatch)
    @Upsert suspend fun save(article: CollectedArticle)
    @Upsert suspend fun save(scan: WeChatScan)
    @Insert suspend fun snapshot(snapshot: ArticleSnapshot)
    @Upsert suspend fun comments(comments: List<CollectedComment>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun visit(visit: ScanArticle)
}
@Database(entities = [CollectedArticle::class, ArticleSnapshot::class, CollectedComment::class, WeChatScan::class, ScanArticle::class, WeChatBatch::class], version = 3)
abstract class WeChatDatabase : RoomDatabase() {
    abstract fun articles(): WeChatDao
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE batches ADD COLUMN articleLimit INTEGER NOT NULL DEFAULT 50")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scans ADD COLUMN batchId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scans_batchId ON scans(batchId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS batches (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, state TEXT NOT NULL, reason TEXT, startedAt INTEGER NOT NULL)")
            }
        }
    }
}
@Module @InstallIn(SingletonComponent::class)
object WeChatDatabaseModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): WeChatDatabase =
        Room.databaseBuilder(context, WeChatDatabase::class.java, "wechat-collection.db").addMigrations(WeChatDatabase.MIGRATION_1_2, WeChatDatabase.MIGRATION_2_3).build()
    @Provides fun dao(db: WeChatDatabase): WeChatDao = db.articles()
}
