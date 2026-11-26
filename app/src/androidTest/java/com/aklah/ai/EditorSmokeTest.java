package com.aklah.ai;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

@RunWith(AndroidJUnit4.class)
public class EditorSmokeTest {

    @Before
    public void clearSavedProjects() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("aklah_projects", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void createSaveAndOpenProjectWithoutCrash() {
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.btnNewProject)).perform(click());

            onView(withId(R.id.etProjectName))
                    .perform(typeText("CI Smoke Project"), closeSoftKeyboard());
            onView(withId(R.id.btnSaveProject)).perform(click());

            onView(withId(R.id.tvRecentEmpty))
                    .check(matches(withText(org.hamcrest.Matchers.containsString("CI Smoke Project"))))
                    .perform(click());

            onView(withId(R.id.tvEditorTitle))
                    .check(matches(withText("CI Smoke Project")));

            onView(withText("Project Save Karein")).check(matches(isDisplayed()));
            onView(withText("Export MP4")).check(matches(isDisplayed()));
            onView(withText("More Tools")).perform(click());
            onView(withText("CLOSE")).check(matches(isDisplayed())).perform(click());
        }
    }
}