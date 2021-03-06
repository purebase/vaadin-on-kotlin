package com.github.vok.framework.flow

import com.github.vok.framework.I18n
import com.vaadin.flow.component.UI

/**
 * Retrieves locale for current UI. Fails if there is no UI (the function is not called from Vaadin's UI thread).
 */
val voki18n: I18n
    get() {
        val locale = UI.getCurrent().locale
        check(locale != null) { "UI.getCurrent().locale can't really return null" }
        return I18n(locale)
    }
