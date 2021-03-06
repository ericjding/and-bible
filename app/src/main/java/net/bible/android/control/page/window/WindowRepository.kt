/*
 * Copyright (c) 2018 Martin Denham, Tuomas Airaksinen and the And Bible contributors.
 *
 * This file is part of And Bible (http://github.com/AndBible/and-bible).
 *
 * And Bible is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * And Bible is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with And Bible.
 * If not, see http://www.gnu.org/licenses/.
 *
 */

package net.bible.android.control.page.window

import android.content.SharedPreferences

import net.bible.android.BibleApplication
import net.bible.android.control.ApplicationScope
import net.bible.android.control.event.ABEventBus
import net.bible.android.control.event.apptobackground.AppToBackgroundEvent
import net.bible.android.control.event.window.CurrentWindowChangedEvent
import net.bible.android.control.page.CurrentPageManager
import net.bible.android.control.page.window.WindowLayout.WindowState
import net.bible.service.common.Logger

import org.apache.commons.lang3.StringUtils
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.util.ArrayList

import javax.inject.Inject
import javax.inject.Provider

@ApplicationScope
open class WindowRepository @Inject constructor(
        // Each window has its own currentPageManagerProvider to store the different state e.g.
        // different current Bible module, so must create new cpm for each window
        private val currentPageManagerProvider: Provider<CurrentPageManager>)
{

    private var windowList: MutableList<Window> = ArrayList()
    var dedicatedLinksWindow = LinksWindow(WindowState.CLOSED, currentPageManagerProvider.get())
        private set

    private var maxWindowNoUsed = 0

    private val logger = Logger(this.javaClass.name)

    //TODO if user presses a link then should also show links window
    val windows: List<Window>
        get() {
            val windows = ArrayList(windowList)
            addLinksWindowIfVisible(windows)
            return windows
        }

    // 1 based screen no
    var activeWindow = getDefaultActiveWindow()
        set(newActiveWindow) {
            if (newActiveWindow != this.activeWindow) {
                field = newActiveWindow
                ABEventBus.getDefault().post(CurrentWindowChangedEvent(this.activeWindow))
            }
        }

    init {
        restoreState()
        ABEventBus.getDefault().safelyRegister(this)
    }

    // links window is still displayable in maximised mode but does not have the requested MAXIMIZED state
    // should only ever be one maximised window
    val visibleWindows: MutableList<Window>
        get() {
            val maximisedWindows = getWindows(WindowState.MAXIMISED)
            return if (!maximisedWindows.isEmpty()) {
                if (!maximisedWindows.contains(dedicatedLinksWindow as Window)) {
                    addLinksWindowIfVisible(maximisedWindows)
                }
                maximisedWindows
            } else {
                getWindows(WindowState.SPLIT)
            }
        }

    val maximisedScreens: List<Window>
        get() = getWindows(WindowState.MAXIMISED)


    // if a window is maximised then show no minimised windows
    val minimisedScreens: List<Window>
        get() = if (isMaximisedState) {
            ArrayList()
        } else {
            getWindows(WindowState.MINIMISED)
        }

    private val isMaximisedState: Boolean
        get() {
            for (window in windows) {
                if (window.windowLayout.state === WindowState.MAXIMISED) {
                    return true
                }
            }
            return false
        }

    val isMultiWindow: Boolean
        get() {
            val windows = visibleWindows
            return windows.size > 1
        }

    private val defaultState: WindowLayout.WindowState
        get() = WindowState.SPLIT

    val firstWindow: Window
        get() = windowList[0]

    /**
     * Return window no larger than any windows created during this session and larger than 0
     */
    private val nextWindowNo: Int
        get() = maxWindowNoUsed + 1



    private fun getDefaultActiveWindow(): Window {
        for (window in windows) {
            if (window.isVisible) {
                return window
            }
        }

        // no suitable window found so add one and make it default
        return addNewWindow(nextWindowNo)
    }

    private fun addLinksWindowIfVisible(windows: MutableList<Window>) {
        if (dedicatedLinksWindow.isVisible) {
            windows.add(dedicatedLinksWindow)
        }
    }

    private fun getWindows(state: WindowState): MutableList<Window> {
        val ws = ArrayList<Window>()
        for (window in windows) {
            if (window.windowLayout.state === state) {
                ws.add(window)
            }
        }
        return ws
    }

    fun getWindow(screenNo: Int): Window? {
        for (window in windows) {
            if (window.screenNo == screenNo) {
                return window
            }
        }
        return null
    }

    fun addNewWindow(): Window {
        // ensure main screen is not maximized
        activeWindow.windowLayout.state = WindowState.SPLIT

        return addNewWindow(nextWindowNo)
    }

    fun getWindowsToSynchronise(sourceWindow: Window?): List<Window> {
        val windows = visibleWindows
        if (sourceWindow != null) {
            windows.remove(sourceWindow)
        }

        return windows
    }

    fun minimise(window: Window) {
        window.windowLayout.state = WindowState.MINIMISED

        // has the active screen been minimised?
        if (activeWindow == window) {
            activeWindow = getDefaultActiveWindow()
        }
    }

    fun close(window: Window) {
        window.windowLayout.state = WindowState.CLOSED

        // links window is just closed not deleted
        if (!window.isLinksWindow) {

            if (!windowList.remove(window)) {
                logger.error("Failed to close window " + window.screenNo)
            }
        }

        // has the active screen been minimised?
        if (activeWindow == window) {
            activeWindow = getDefaultActiveWindow()
        }

    }

    fun moveWindowToPosition(window: Window, position: Int) {
        val originalWindowIndex = windowList.indexOf(window)

        if (originalWindowIndex == -1) {
            logger.warn("Attempt to move missing window")
            return
        }
        if (position > windowList.size) {
            logger.warn("Attempt to move window beyond end of window list")
            return
        }

        windowList.removeAt(originalWindowIndex)

        windowList.add(position, window)
    }

    private fun addNewWindow(screenNo: Int): Window {
        val newScreen = Window(screenNo, defaultState, currentPageManagerProvider.get())
        maxWindowNoUsed = Math.max(maxWindowNoUsed, screenNo)
        windowList.add(newScreen)
        return newScreen
    }

    /**
     * If app moves to background then save current state to allow continuation after return
     *
     * @param appToBackgroundEvent Event info
     */
    fun onEvent(appToBackgroundEvent: AppToBackgroundEvent) {
        if (appToBackgroundEvent.isMovedToBackground) {
            saveState()
        }
    }

    /** save current page and document state  */
    private fun saveState() {
        logger.info("Save instance state for screens")
        val settings = BibleApplication.application.appStateSharedPreferences
        saveState(settings)
    }

    /** restore current page and document state  */
    private fun restoreState() {
        try {
            logger.info("Restore instance state for screens")
            val application = BibleApplication.application
            val settings = application.appStateSharedPreferences
            restoreState(settings)
        } catch (e: Exception) {
            logger.error("Restore error", e)
        }
        activeWindow = getDefaultActiveWindow()
    }

    /** called during app close down to save state
     *
     * @param outState
     */
    private fun saveState(outState: SharedPreferences) {
        logger.info("save state")
        try {

            val windowRepositoryStateObj = JSONObject()
            val windowStateArray = JSONArray()
            for (window in windowList) {
                try {
                    if (window.windowLayout.state !== WindowState.CLOSED) {
                        windowStateArray.put(window.stateJson)
                    }
                } catch (je: JSONException) {
                    logger.error("Error saving screen state", je)
                }

            }
            windowRepositoryStateObj.put("windowState", windowStateArray)

            val editor = outState.edit()
            editor.putString("windowRepositoryState", windowRepositoryStateObj.toString())
            editor.apply()
        } catch (je: JSONException) {
            logger.error("Saving window state", je)
        }

    }

    /** called during app start-up to restore previous state
     *
     * @param inState
     */
    private fun restoreState(inState: SharedPreferences) {
        logger.info("restore state")
        val windowRepositoryStateString = inState.getString("windowRepositoryState", null)
        if (StringUtils.isNotEmpty(windowRepositoryStateString)) {
            try {
                val windowRepositoryState = JSONObject(windowRepositoryStateString)
                val windowState = windowRepositoryState.getJSONArray("windowState")
                if (windowState.length() > 0) {

                    // remove current (default) state before restoring
                    windowList.clear()

                    for (i in 0 until windowState.length()) {
                        try {
                            val screenState = windowState.getJSONObject(i)
                            val window = Window(currentPageManagerProvider.get())
                            window.restoreState(screenState)

                            maxWindowNoUsed = Math.max(maxWindowNoUsed, window.screenNo)

                            windowList.add(window)
                        } catch (je: JSONException) {
                            logger.error("Error restoring screen state", je)
                        }

                    }
                }
            } catch (je: JSONException) {
                logger.error("Error restoring screen state", je)
            }

        }
    }
}
