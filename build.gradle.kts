plugins {
    alias(libs.plugins.android.application) apply false
    // Applied by :app only when its (untracked) google-services.json exists;
    // declared here so the versions resolve either way. See SETUP.md.
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.google.services) apply false
}
