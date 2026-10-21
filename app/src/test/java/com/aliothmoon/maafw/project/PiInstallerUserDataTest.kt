package com.aliothmoon.maafw.project

import com.aliothmoon.maafw.constant.AppPaths
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** 真正运行解包与文件删除，覆盖重解、失败重试和符号链接边界。 */
class PiInstallerUserDataTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var base: File
    private val resources = mapOf("interface.json" to "{}", "resource/current.json" to "new")
    private val userFiles = mapOf(
        "config/Inventory/servants.json" to "{\"owned\":[1,2]}",
        "config/Inventory/craft_essences.json" to "{\"owned\":[3]}",
        "config/Battle/队伍.json" to "{\"team\":4}",
        "config/BondCompletion/auto_memory.json" to "{\"progress\":5}",
    )

    @Before
    fun setup() {
        base = temp.newFolder("external")
        mockkObject(AppPaths)
        every { AppPaths.ROOT } returns base
    }

    @After
    fun teardown() = unmockkObject(AppPaths)

    private fun installer(pkg: PiPackage = MapPiPackage(resources), version: Int = 11) = PiInstaller(pkg, version)

    private fun seedUserData(root: File) {
        userFiles.forEach { (name, content) ->
            File(root, name).apply { parentFile!!.mkdirs(); writeText(content) }
        }
        File(root, "debug/record/stale.json").apply { parentFile!!.mkdirs(); writeText("stale") }
        File(root, "resource/obsolete.json").writeText("obsolete")
    }

    private fun assertUserData(root: File) {
        userFiles.forEach { (name, content) -> assertEquals(name, content, File(root, name).readText()) }
    }

    @Test
    fun `manual reinstall preserves inventory teams and bond progress while refreshing resources`() {
        for ((index, pkg) in listOf(MapPiPackage(resources), ZipPiPackage(resources)).withIndex()) {
            base = temp.newFolder("external$index")
            every { AppPaths.ROOT } returns base
            val pi = installer(pkg)
            val root = pi.ensureInstalled()
            seedUserData(root)

            pi.reinstall()

            assertUserData(root)
            assertFalse(File(root, "debug").exists())
            assertFalse(File(root, "resource/obsolete.json").exists())
            assertEquals("new", File(root, "resource/current.json").readText())
            assertEquals("11", File(base, PiInstaller.PI_MARKER_NAME).readText())
        }
    }

    @Test
    fun `failed manual extraction and retry keep user data and only commit valid marker`() {
        val root = installer().ensureInstalled()
        seedUserData(root)
        val broken = ZipPiPackage(mapOf("resource/partial.json" to "partial"))

        assertThrows(PiUnpackException::class.java) { installer(broken).reinstall() }
        assertUserData(root)
        assertFalse(File(base, PiInstaller.PI_MARKER_NAME).exists())

        installer().reinstall()
        assertUserData(root)
        assertEquals("11", File(base, PiInstaller.PI_MARKER_NAME).readText())
        assertFalse(File(root, "resource/partial.json").exists())
    }

    @Test
    fun `upgrade preserves config even when previous root manifest incorrectly lists it`() {
        val root = installer().ensureInstalled()
        seedUserData(root)
        File(base, PiInstaller.PI_ROOTS_NAME).appendText("\nconfig\n")

        installer(version = 12).ensureInstalled()

        assertUserData(root)
        assertEquals("12", File(base, PiInstaller.PI_MARKER_NAME).readText())
    }

    @Test
    fun `package cannot overwrite the reserved user data directory`() {
        val root = installer().ensureInstalled()
        seedUserData(root)
        for (pkg in listOf(MapPiPackage(resources + ("config/Inventory/servants.json" to "overwrite")),
                           ZipPiPackage(resources + ("config/Inventory/servants.json" to "overwrite")))) {
            assertThrows(PiUnpackException::class.java) { installer(pkg).reinstall() }
            assertUserData(root)
            assertFalse(File(base, PiInstaller.PI_MARKER_NAME).exists())
        }
    }

    @Test
    fun `equivalent config archive paths cannot bypass user data protection`() {
        val root = installer().ensureInstalled()
        seedUserData(root)
        for (name in listOf("./config/Inventory/servants.json", "/config//Inventory/servants.json",
                            "config\\Inventory\\servants.json")) {
            val pkg = ZipPiPackage(resources + (name to "overwrite"))
            assertThrows(PiUnpackException::class.java) { installer(pkg).reinstall() }
            assertUserData(root)
            assertFalse(File(base, PiInstaller.PI_MARKER_NAME).exists())
        }
    }

    @Test
    fun `dot prefixed resource archive paths cannot delete preserved config`() {
        val root = installer().ensureInstalled()
        seedUserData(root)
        val pkg = ZipPiPackage(mapOf("./interface.json" to "{}", "./resource/current.json" to "refreshed"))

        installer(pkg).reinstall()

        assertUserData(root)
        assertEquals("refreshed", File(root, "resource/current.json").readText())
        assertEquals("11", File(base, PiInstaller.PI_MARKER_NAME).readText())
    }

    @Test
    fun `manual reinstall never follows symlinks in discarded directories`() {
        val root = installer().ensureInstalled()
        seedUserData(root)
        val outside = temp.newFolder("outside")
        File(outside, "keep.txt").writeText("outside")
        try {
            Files.createSymbolicLink(File(root, "debug/escape").toPath(), outside.toPath())
        } catch (error: IOException) {
            assumeNoException("symbolic links unavailable on this host", error)
        } catch (error: UnsupportedOperationException) {
            assumeNoException(error)
        }

        installer().reinstall()

        assertEquals("outside", File(outside, "keep.txt").readText())
        assertUserData(root)
        assertFalse(File(root, "debug").exists())
    }

    @Test
    fun `preserved config symlink is not traversed or changed`() {
        val root = installer().ensureInstalled()
        val outside = temp.newFolder("user-config")
        File(outside, "keep.txt").writeText("config")
        val config = File(root, "config").toPath()
        try {
            Files.createSymbolicLink(config, outside.toPath())
        } catch (error: IOException) {
            assumeNoException("symbolic links unavailable on this host", error)
        } catch (error: UnsupportedOperationException) {
            assumeNoException(error)
        }

        installer().reinstall()

        assertTrue(Files.isSymbolicLink(config))
        assertEquals("config", File(outside, "keep.txt").readText())
    }
}
