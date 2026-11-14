package com.example

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.ui.auth.findActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("VoiceLedger", appName)
  }

  @Test
  @Config(sdk = [30])
  fun `main activity launches and resumes cleanly on Android 11`() {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        assertEquals(false, activity.isFinishing)
        val wrapped = ContextWrapper(ContextWrapper(activity))
        assertSame(activity, wrapped.findActivity())
      }
      scenario.moveToState(Lifecycle.State.CREATED)
      scenario.moveToState(Lifecycle.State.RESUMED)
      scenario.onActivity { activity ->
        assertEquals(false, activity.isFinishing)
      }
    }
    val appContext = ApplicationProvider.getApplicationContext<Context>()
    assertNull(appContext.findActivity())
  }
}
