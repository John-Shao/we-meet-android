package com.we.meet.ui.records

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Fixture-only screens: do not initialize account, push, analytics or production repositories. */
class IsolatedRecordsRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
