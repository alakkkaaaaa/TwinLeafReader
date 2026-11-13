package com.localreader.twinleaf

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.twinleaf.data.*
import com.localreader.twinleaf.ui.LibraryScreen
import com.localreader.twinleaf.ui.theme.TwinLeafTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShelfDragTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo by lazy { LibraryRepository(context) }
    private var opened = 0
    private var generation by mutableIntStateOf(0)
    private val imports = mutableListOf<String?>()

    private fun setup(inFolder: Boolean = false, count: Int = 5, extraFolder: Boolean = false): String {
        context.getSharedPreferences("twinleaf_library", 0).edit().clear().commit()
        val folder = repo.createFolder("拖动测试系列")
        if(extraFolder) repo.createFolder("第二个系列")
        val array = JSONArray()
        repeat(count) { i -> array.put(JSONObject().put("id", "drag-$i").put("uri", "file:///drag-$i.pdf")
            .put("title", "测试第${i + 1}卷").put("format", "PDF").put("lastOpenedAt", count - i)
            .put("importedAt", i).put("category", if(i == 1) "教科书" else "小说")
            .put("folderId", if(inFolder) folder.id else JSONObject.NULL)) }
        context.getSharedPreferences("twinleaf_library", 0).edit().putString("books", array.toString()).commit()
        var books by mutableStateOf(repo.load())
        var current by mutableStateOf(if(inFolder) folder.id else null)
        compose.setContent { TwinLeafTheme { key(generation) {
            LibraryScreen(books, onOpen = { opened++ }, onAdd = { imports.add(it) }, onRemove = {},
                onChanged = { books = repo.load() }, folderId = current, onFolderChange = { current = it })
        } } }
        compose.waitForIdle()
        return folder.id
    }

    private fun drag(source: String, target: String, cancel: Boolean = false, edge: Int = 0) {
        val grid = compose.onNodeWithTag("shelf-drag-grid")
        val origin = grid.fetchSemanticsNode().boundsInRoot.topLeft
        val start = compose.onNodeWithTag(source).fetchSemanticsNode().boundsInRoot.center - origin
        val targetRect = compose.onNodeWithTag(target).fetchSemanticsNode().boundsInRoot
        val end = (when(edge) { -1 -> Offset(targetRect.left+4f,targetRect.center.y)
            1 -> Offset(targetRect.right-4f,targetRect.center.y); else -> targetRect.center }) - origin
        grid.performTouchInput {
            down(start)
            advanceEventTime(700)
            for (i in 1..20) moveTo(start + (end - start) * (i / 20f), delayMillis = 30)
            if(cancel) cancel() else up()
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(300)
        val output=java.io.File(context.getExternalFilesDir(null),"verification").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(output,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
    }

    @Test fun foldersMixWithBooksPersistAndNeverNest() {
        val first=setup(extraFolder=true)
        val second=repo.folders().last().id
        val metadata=repo.load()
        drag("shelf-folder:$first","shelf-book:drag-2")
        assertTrue(repo.rootShelfOrder().indexOf("folder:$first") > repo.rootShelfOrder().indexOf("book:drag-2"))
        drag("shelf-folder:$second","shelf-folder:$first")
        val saved=repo.rootShelfOrder()
        assertTrue(saved.indexOf("folder:$second") > saved.indexOf("folder:$first"))
        assertEquals(metadata,repo.load());assertEquals(2,repo.folders().size)
        drag("shelf-folder:$first","shelf-book:drag-0",cancel=true)
        assertEquals(saved,repo.rootShelfOrder())
        compose.onNodeWithText("重命名文件夹").assertDoesNotExist()
        compose.runOnIdle { generation++ }
        compose.waitForIdle()
        assertEquals(saved,LibraryRepository(context).rootShelfOrder())
        val book=compose.onNodeWithTag("shelf-book:drag-2").fetchSemanticsNode().boundsInRoot
        val folder=compose.onNodeWithTag("shelf-folder:$first").fetchSemanticsNode().boundsInRoot
        assertTrue(folder.left>book.left || folder.top>book.top)
        screenshot("v202-mixed-shelf")
    }

    @Test fun bookAtFolderEdgeReordersButCenterMovesInside() {
        val folder=setup()
        drag("shelf-book:drag-0","shelf-folder:$folder",edge=-1)
        assertEquals("book:drag-0",repo.rootShelfOrder().first())
        assertNull(repo.load().first { it.id=="drag-0" }.folderId)
        drag("shelf-book:drag-0","shelf-folder:$folder")
        assertEquals(folder,repo.load().first { it.id=="drag-0" }.folderId)
    }

    @Test fun mergedMenusKeepAllActionsAndThemeCyclesWithoutDialog() {
        val folder=setup()
        compose.onNodeWithContentDescription("添加书籍").assertDoesNotExist()
        compose.onNodeWithContentDescription("新建文件夹").assertDoesNotExist()
        compose.onNodeWithContentDescription("添加内容").performClick()
        compose.onNodeWithText("新建文件夹").assertExists()
        screenshot("v202-add-menu")
        compose.onNodeWithText("导入书籍").performClick()
        assertEquals(listOf<String?>(null),imports)
        compose.onNodeWithContentDescription("添加内容").performClick()
        compose.onNodeWithText("新建文件夹").performClick()
        compose.onNodeWithContentDescription("文件夹名称").performTextInput("新增系列")
        compose.onNodeWithText("保存").performClick()
        assertEquals(2,repo.folders().size)
        val beforeMenuSort=repo.rootShelfOrder()
        compose.onNodeWithContentDescription("书架更多").performClick()
        compose.onNodeWithText("书架排序").performClick()
        compose.onNodeWithContentDescription("下移书架项目：folder:$folder").performClick()
        compose.onNodeWithText("保存排序").performClick()
        assertEquals(beforeMenuSort[1],repo.rootShelfOrder().first())
        compose.onNodeWithContentDescription("书架更多").performClick()
        screenshot("v202-more-menu")
        compose.onNodeWithText("格式筛选").performClick()
        compose.onNode(hasText("PDF") and hasAnyAncestor(isDialog()),useUnmergedTree=true).performClick()
        compose.onNodeWithText("格式：PDF").assertExists()
        compose.onNodeWithContentDescription("书架更多").performClick()
        compose.onNodeWithText("批量管理").performClick()
        compose.onNodeWithTag("shelf-book:drag-0").performClick()
        compose.onNodeWithContentDescription("批量移入文件夹").assertIsEnabled()
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { com.localreader.twinleaf.ui.theme.setThemeMode(context,com.localreader.twinleaf.ui.theme.ThemeMode.SYSTEM) }
        for(expected in listOf("LIGHT","DARK","SYSTEM")) {
            compose.onNodeWithContentDescription("切换亮暗模式").performClick()
            compose.runOnIdle { assertEquals(expected,context.getSharedPreferences("reader_visual_settings",0).getString("themeMode",null)) }
            compose.onNodeWithText("外观").assertDoesNotExist()
        }
        compose.onNodeWithContentDescription("书架更多").performClick()
        compose.onNodeWithText("格式筛选").performClick()
        compose.onNode(hasText("全部") and hasAnyAncestor(isDialog()),useUnmergedTree=true).performClick()
        compose.onNodeWithTag("shelf-folder:$folder").performClick()
        compose.onNodeWithContentDescription("添加内容").performClick()
        compose.onNodeWithText("新建文件夹").assertDoesNotExist()
        compose.onNodeWithText("从书架移入已有书籍").assertExists()
        compose.onNodeWithText("导入到此文件夹").performClick()
        assertEquals(folder,imports.last())
        compose.onNodeWithContentDescription("添加内容").performClick()
        compose.onNodeWithText("从书架移入已有书籍").performClick()
        compose.onNodeWithContentDescription("选择书籍：测试第1卷").performClick()
        compose.onNodeWithText("保存（1）").performClick()
        assertEquals(folder,repo.load().first { it.id=="drag-0" }.folderId)
        compose.onNodeWithContentDescription("书架更多").performClick()
        compose.onNodeWithText("文件夹设置").performClick()
        compose.onNodeWithText("更换文件夹封面").assertExists()
    }

    @Test fun legacyOrderMigratesAndMembershipChangesKeepMixedPositions() {
        val folder=setup()
        val prefs=context.getSharedPreferences("twinleaf_library",0)
        prefs.edit().putString("rootBookOrder",JSONArray(listOf("drag-4","drag-0","drag-1","drag-2","drag-3")).toString()).commit()
        assertEquals(listOf("folder:$folder","book:drag-4","book:drag-0","book:drag-1","book:drag-2","book:drag-3"),repo.rootShelfOrder())
        repo.reorderRootEntries(listOf("book:drag-4","folder:$folder","book:drag-0","book:drag-1","book:drag-2","book:drag-3"))
        repo.moveToFolder(setOf("drag-0"),folder)
        assertEquals(listOf("book:drag-4","folder:$folder","book:drag-1","book:drag-2","book:drag-3"),repo.rootShelfOrder())
        repo.moveToFolder(setOf("drag-0"),null)
        assertEquals("book:drag-0",repo.rootShelfOrder().last())
        repo.deleteFolder(folder)
        assertFalse(repo.rootShelfOrder().contains("folder:$folder"))
        assertEquals(5,repo.rootShelfOrder().size)
    }

    @Test fun rootDragReordersThenDropsIntoFolderAndCompacts() {
        val folder = setup()
        drag("shelf-book:drag-0", "shelf-book:drag-2")
        assertEquals(listOf("drag-1", "drag-2", "drag-0", "drag-3", "drag-4"), repo.rootBookOrder())
        assertEquals(0, opened)
        drag("shelf-book:drag-0", "shelf-folder:$folder")
        assertEquals(folder, repo.load().first { it.id == "drag-0" }.folderId)
        compose.onNodeWithTag("shelf-book:drag-0").assertDoesNotExist()
        assertEquals(listOf("drag-1", "drag-2", "drag-3", "drag-4"), repo.rootBookOrder())
        val before = compose.onNodeWithTag("shelf-book:drag-2").fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithTag("shelf-book:drag-3").fetchSemanticsNode().boundsInRoot
        assertTrue(next.left > before.left || next.top > before.top)
        val output = java.io.File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(output, "v201-shelf-drop.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun canceledDragDoesNotPersistAndTapStillOpens() {
        setup()
        drag("shelf-book:drag-0", "shelf-book:drag-2", cancel = true)
        assertNull(repo.rootBookOrder())
        assertEquals(0, opened)
        compose.onNodeWithTag("shelf-book:drag-0").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun folderDragUsesManualOrderAndCanReturnBookToRoot() {
        val folder = setup(inFolder = true)
        repo.updateFolderSort(folder, FolderSort.TITLE, false)
        drag("shelf-book:drag-0", "shelf-book:drag-2")
        val saved = repo.folders().first { it.id == folder }
        assertEquals(FolderSort.MANUAL, saved.sort)
        assertEquals(listOf("drag-1", "drag-2", "drag-0", "drag-3", "drag-4"), saved.manualOrder)
        val grid = compose.onNodeWithTag("shelf-drag-grid")
        val origin = grid.fetchSemanticsNode().boundsInRoot.topLeft
        val start = compose.onNodeWithTag("shelf-book:drag-0").fetchSemanticsNode().boundsInRoot.center - origin
        grid.performTouchInput {
            down(start); advanceEventTime(700)
            val end = Offset(start.x, 20f)
            for (i in 1..20) moveTo(start + (end - start) * (i / 20f), delayMillis = 30)
            up()
        }
        compose.waitForIdle()
        assertNull(repo.load().first { it.id == "drag-0" }.folderId)
        assertFalse(repo.folders().first { it.id == folder }.manualOrder.contains("drag-0"))
    }

    @Test fun filteredDragPreservesHiddenBookAndHasNoEmptySlot() {
        setup()
        compose.onNodeWithText("小说", useUnmergedTree = true).performClick()
        drag("shelf-book:drag-0", "shelf-book:drag-2")
        assertEquals(listOf("drag-2", "drag-1", "drag-0", "drag-3", "drag-4"), repo.rootBookOrder())
        compose.onNodeWithText("全部", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("shelf-book:drag-1").assertExists()
    }

    @Test fun holdingAtBottomAutoScrollsWithoutLosingTheDraggedBook() {
        setup(count = 45)
        val grid = compose.onNodeWithTag("shelf-drag-grid")
        val rect = grid.fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithTag("shelf-book:drag-0").fetchSemanticsNode().boundsInRoot.center - rect.topLeft
        val end = Offset(start.x, rect.height - 15f)
        compose.mainClock.autoAdvance = false
        grid.performTouchInput {
            down(start); advanceEventTime(700)
            for (i in 1..25) moveTo(start + (end - start) * (i / 25f), delayMillis = 30)
        }
        compose.mainClock.advanceTimeBy(1800)
        grid.performTouchInput { moveTo(end - Offset(0f, 90f), delayMillis = 40); up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val order = repo.rootBookOrder()!!
        assertEquals(45, order.size)
        assertEquals(45, order.toSet().size)
        assertTrue("Dragged book must reach a later row", order.indexOf("drag-0") > 8)
        assertEquals(0, opened)
    }
}
