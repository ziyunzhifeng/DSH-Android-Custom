package io.github.jackwener.dsh.core.wechat

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jackwener.dsh.core.adb.LocalAdbRepository
import io.github.jackwener.dsh.core.data.SecretStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WeChatDatabaseTest {
    private lateinit var db: WeChatDatabase
    private lateinit var collection: WeChatCollection
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, WeChatDatabase::class.java).allowMainThreadQueries().build()
        collection = WeChatCollection(db.articles(), db, LocalAdbRepository(context, SecretStore(context)))
    }
    @After fun close() { db.close() }
    private fun measured(id: String, reads: String? = "75", account: String = "a", comments: String? = null, items: JSONArray = JSONArray()): JSONObject =
        JSONObject().put("collected_at_ms", 1000).put("article", JSONObject().put("title", id).put("account",account)
            .put("account_identity",account).put("account_identity_verified",true).put("article_identity",id).put("read_text",reads ?: JSONObject.NULL)
            .put("comment_count_text", comments ?: JSONObject.NULL).put("comments",items).put("comment_status","loaded_partial"))
    @Test fun `ranking uses lower bound reads then comment count with nulls last`() = runBlocking {
        collection.persist(measured("unknown",null))
        collection.persist(measured("small","75"))
        collection.persist(measured("big","10万+"))
        collection.persist(measured("tie","75",comments="3"))
        assertEquals(listOf("big","tie","small","unknown"), db.articles().hot().first().map { it.title })
        assertTrue(db.articles().article(WeChatMetrics.id("big"))!!.readLowerBound)
    }
    @Test fun `resampling deduplicates article comments and preserves raw snapshots`() = runBlocking {
        val items = JSONArray().put(JSONObject().put("id","c1").put("author","作者").put("text","留言"))
        collection.persist(measured("same",items=items))
        collection.persist(measured("same",reads="80",items=items))
        assertEquals(1,db.articles().hot().first().size)
        assertEquals("80",db.articles().article(WeChatMetrics.id("same"))!!.readText)
        assertEquals(1,db.articles().comments(WeChatMetrics.id("same")).size)
        val cursor = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM snapshots")
        cursor.use { assertTrue(it.moveToFirst()); assertEquals(2,it.getInt(0)) }
    }
    @Test fun `scan enforces account scope unique count stop and limit`() = runBlocking {
        val scan=WeChatScan("scan","session",WeChatMetrics.id("a"),"a",limit=2)
        db.articles().save(scan)
        collection.persist(measured("one"),"session")
        collection.persist(measured("one",reads="90"),"session")
        assertEquals(1,db.articles().count("scan"))
        try { collection.persist(measured("other",account="b"),"session"); fail("wrong account accepted") } catch (_: IllegalStateException) {}
        collection.persist(measured("two"),"session")
        assertEquals("completed",db.articles().scan("scan")!!.state)
        try { collection.persist(measured("three"),"session"); fail("over limit accepted") } catch (_: IllegalStateException) {}
        db.articles().state("scan","stopped",null)
        try { collection.persist(measured("one"),"session"); fail("stopped scan accepted") } catch (_: IllegalStateException) {}
        assertEquals(2,db.articles().count("scan"))
    }
    @Test fun `last article remains open for remaining comment batches`() = runBlocking {
        db.articles().save(WeChatScan("batch","session",WeChatMetrics.id("a"),"a",limit=1))
        val first=measured("one"); first.getJSONObject("article").put("comments_next_offset",500)
        collection.persist(first,"session")
        assertEquals("running",db.articles().scan("batch")!!.state)
        collection.persist(measured("one"),"session")
        assertEquals("completed",db.articles().scan("batch")!!.state)
        assertEquals(1,db.articles().count("batch"))
    }
    @Test fun `identity missing is rejected instead of merging equal titles`() = runBlocking {
        val data=measured("title");data.getJSONObject("article").remove("article_identity")
        try { collection.persist(data); fail("missing identity accepted") } catch (_: IllegalStateException) {}
        assertTrue(db.articles().hot().first().isEmpty())
    }
    @Test fun `batch binds accounts from CDP deduplicates completed accounts and rejects cross account writes`() = runBlocking {
        val dao = db.articles()
        val batch = WeChatBatch("followed", "session")
        dao.save(batch)
        collection.navigationAllowed("session") // List navigation works before an article exists.
        collection.registerAccount(batch, measured("a1"))
        collection.persist(measured("a1"), "session")
        try { collection.registerAccount(batch, measured("b1", account="b")); fail("changed running account") } catch (_: IllegalStateException) {}
        try { collection.persist(measured("b1", account="b"),"session"); fail("mixed accounts") } catch (_: IllegalStateException) {}
        collection.finishAccount("session", "visible_list_end")
        assertTrue(collection.registerAccount(batch, measured("a1")).getBoolean("already_visited"))
        collection.registerAccount(batch, measured("b1",account="b"))
        collection.persist(measured("b1",account="b"),"session")
        assertEquals("b",collection.active("session").account)
        assertEquals(2,dao.batchScans(batch.id).size)
        dao.batchState(batch.id,"stopped","user_stopped")
        try { collection.persist(measured("b2",account="b"),"session"); fail("stopped batch accepted data") } catch (_: IllegalStateException) {}
        assertEquals(2,dao.hot().first().size)
    }
    @Test fun `batch refuses premature finish and retains skipped account records`() = runBlocking {
        val dao=db.articles();val batch=WeChatBatch("followed","session");dao.save(batch)
        collection.registerAccount(batch,measured("a1"))
        try { collection.finishBatch("session","visible_list_end"); fail("active account lost") } catch (_: IllegalStateException) {}
        collection.finishAccount("session","blocked")
        collection.skipAccount("session","不可访问账号","blocked")
        assertEquals("skipped",dao.batchScans(batch.id).last().state)
        collection.finishBatch("session","visible_list_end")
        assertEquals("completed",dao.batch(batch.id)!!.state)
        assertTrue(dao.hot().first().isEmpty()) // Skipped accounts never create zero-valued articles.
    }
    @Test fun `account heat preserves unknown coverage and does not inflate revisited articles`() = runBlocking {
        collection.persist(measured("a1","10万+",comments="3"))
        collection.persist(measured("a1","10万+",comments="4"))
        collection.persist(measured("a2",null))
        collection.persist(measured("b1",null,account="b"))
        val heat=db.articles().accountHeat().first()
        assertEquals(listOf("a","b"),heat.map { it.account })
        assertEquals(2,heat[0].articles);assertEquals(1,heat[0].measuredReads)
        assertEquals(100000L,heat[0].totalReads);assertEquals(1,heat[0].lowerBounds)
        assertEquals(4L,heat[0].totalComments);assertNull(heat[1].totalReads)
    }
    @Test fun `v1 and v2 migrations preserve articles snapshots comments and scan checkpoints`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in 1..2) {
        val name="wechat-migration-test-$version.db";context.deleteDatabase(name)
        val schema=JSONObject(checkNotNull(javaClass.classLoader!!.getResource("wechat-schemas/$version.json")).readText()).getJSONObject("database")
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            old.execSQL("INSERT INTO articles VALUES ('article','account','作者','旧文章','75',75,0,NULL,NULL,1,'loaded_partial',1000)")
            old.execSQL("INSERT INTO scans (id,sessionId,accountId,account,`limit`,state,reason,startedAt) VALUES ('scan','session','account','作者',50,'stopped','user_stopped',1000)")
            old.execSQL("INSERT INTO scan_articles VALUES ('scan','article')")
            old.execSQL("INSERT INTO comments VALUES ('article','comment','读者','留言',NULL,1000)")
            old.execSQL("INSERT INTO snapshots VALUES ('snapshot','article','scan','{}',1000)")
            if (version == 2) old.execSQL("INSERT INTO batches VALUES ('oldbatch','oldsession','stopped','user_stopped',1000)")
            old.version=version
        }
        val migrated=Room.databaseBuilder(context,WeChatDatabase::class.java,name)
            .addMigrations(WeChatDatabase.MIGRATION_1_2,WeChatDatabase.MIGRATION_2_3).allowMainThreadQueries().build()
        try {
            assertEquals("旧文章",migrated.articles().article("article")!!.title)
            assertEquals(1,migrated.articles().count("scan"))
            assertNull(migrated.articles().scan("scan")!!.batchId)
            assertEquals("留言",migrated.articles().comments("article").single().text)
            migrated.openHelper.readableDatabase.query("SELECT COUNT(*) FROM snapshots").use { assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0)) }
            migrated.articles().save(WeChatBatch("batch","batchsession"))
            assertNotNull(migrated.articles().batch("batch"))
            if (version == 2) assertEquals(50,migrated.articles().batch("oldbatch")!!.articleLimit)
        } finally { migrated.close();context.deleteDatabase(name) }
        }
    }

    @Test fun `batch never merges accounts by unverified nickname`() = runBlocking {
        val batch=WeChatBatch("followed","session");db.articles().save(batch)
        val data=measured("article");data.getJSONObject("article").put("account_identity_verified",false)
        try { collection.registerAccount(batch,data);fail("nickname used as identity") } catch (_: IllegalStateException) {}
        assertTrue(db.articles().batchScans(batch.id).isEmpty())
    }

    @Test fun `batch article limit is applied independently and active batch is shown before newer stopped batch`() = runBlocking {
        val batch=WeChatBatch("quick","session",articleLimit=1,startedAt=1);db.articles().save(batch)
        db.articles().save(WeChatBatch("later","other",state="stopped",startedAt=2))
        assertEquals("quick",db.articles().latestBatch().first()!!.id)
        collection.registerAccount(batch,measured("a1"));collection.persist(measured("a1"),"session")
        assertEquals("completed",db.articles().forSession("session")!!.state)
        collection.registerAccount(batch,measured("b1",account="b"));collection.persist(measured("b1",account="b"),"session")
        assertEquals(listOf(1,1),db.articles().batchScans(batch.id).map { db.articles().count(it.id) })
        assertTrue(db.articles().batchScans(batch.id).all { it.limit == 1 && it.state == "completed" })
    }

    @Test fun `leaving WeChat pauses batch and account without losing progress or accepting more actions`() = runBlocking {
        val batch=WeChatBatch("followed","session");db.articles().save(batch)
        collection.registerAccount(batch,measured("a1"));collection.persist(measured("a1"),"session")
        collection.pauseForForegroundChange("session")
        assertEquals("paused",db.articles().batch(batch.id)!!.state)
        assertEquals("left_wechat",db.articles().batch(batch.id)!!.reason)
        assertEquals("paused",db.articles().forSession("session")!!.state)
        assertEquals(1,db.articles().count(db.articles().forSession("session")!!.id))
        try { collection.navigationAllowed("session");fail("paused navigation allowed") } catch (_: IllegalStateException) {}
        try { collection.persist(measured("a2"),"session");fail("paused write allowed") } catch (_: IllegalStateException) {}
    }

    @Test fun `paused scan cannot reopen ADB through debug target discovery`() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val session=WeChatScanPolicy.PREFIX+"stopped"
        db.articles().save(WeChatBatch("batch",session,state="paused"))
        val tools=io.github.jackwener.dsh.core.capability.tools.WeChatTools(LocalAdbRepository(context,SecretStore(context)),collection)
        val result=tools.tools.first { it.name == "wechat_debug_targets" }.invoke(
            kotlinx.serialization.json.buildJsonObject {},io.github.jackwener.dsh.core.capability.CallContext(session,"test",false))
        assertFalse(result.ok)
        assertEquals("Batch is not running",result.errorMessage)
    }

}
