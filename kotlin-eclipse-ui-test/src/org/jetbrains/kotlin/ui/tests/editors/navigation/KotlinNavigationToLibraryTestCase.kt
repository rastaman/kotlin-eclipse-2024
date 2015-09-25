package org.jetbrains.kotlin.ui.tests.editors.navigation

import java.io.File
import org.eclipse.core.runtime.Path
import org.eclipse.jdt.core.JavaModelException
import org.jetbrains.kotlin.testframework.editor.KotlinProjectTestCase
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.jetbrains.kotlin.ui.editors.KotlinEditor
import org.jetbrains.kotlin.psi.JetFile
import org.jetbrains.kotlin.ui.tests.editors.navigation.library.getTestLibrary
import org.jetbrains.kotlin.ui.tests.editors.navigation.library.clean
import org.junit.AfterClass
import org.jetbrains.kotlin.core.log.KotlinLogger

public open class KotlinNavigationToLibraryTestCase: KotlinSourcesNavigationTestCase() {
    override fun getParsedFile(editor: KotlinEditor): JetFile =
            editor.parsedFile!!

    override val testDataPath: String =
        "testData/navigation/lib"

    @Before
    override fun configure() {
        super.configure()
        try
        {
            KotlinLogger.logInfo("[KNTL LOG MESSAGE] Getting library.")
            val testData = getTestLibrary()
            KotlinLogger.logInfo("[KNTL LOG MESSAGE] Got library.")
            val libPath = Path(testData.libPath)
            val srcPath = Path(testData.srcPath)
            getTestProject().addLibrary(libPath, srcPath)
        }
        catch (e:JavaModelException) {
            throw RuntimeException(e)
        }
    }
    
    companion object {
        @AfterClass
        @JvmStatic
        fun afterAllTests() {
            KotlinLogger.logInfo("[KNTL LOG MESSAGE] Beginning to clean.")
            getTestLibrary().clean()
            KotlinLogger.logInfo("[KNTL LOG MESSAGE] Cleaning finished.")
        }
    }
}