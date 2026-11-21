# CarHome notes for Claude
- Built WITHOUT Gradle/Android Studio: `./build.sh` uses aapt, javac, dx (dalvik-exchange), zipalign, apksigner.
- Toolchain is Ubuntu's android-sdk-platform-23 (API 23 android.jar), minSdk 21, targetSdk 23.
- Java 8 source level, and NO lambdas or method references (old dx can't handle them). Use anonymous classes.
- No AndroidX or other libraries. The UI is built in code (no layout XML).
- Icons: Tabler outline SVGs converted to VectorDrawables (res/drawable/ti_*.xml).
- Never commit or regenerate carhome.keystore. CI gets it from the KEYSTORE_BASE64 secret.
- Don't bump versions by hand. build.sh sets versionCode = 100 + commit count and versionName = <manifest versionName>.<commit count>. The manifest versionName is only the milestone base (e.g. 1.5).
- Every push to main publishes a GitHub Release (keeps the last 10). Never force-push or rewrite main: a lower commit count gives a lower versionCode, which won't install as an update.
- Commit messages: always ONE line, Conventional Commits style (`type(scope): summary`), no body.
